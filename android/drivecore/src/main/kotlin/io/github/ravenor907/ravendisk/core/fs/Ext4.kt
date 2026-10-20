package io.github.ravenor907.ravendisk.core.fs

import io.github.ravenor907.ravendisk.core.io.BlockDevice
import io.github.ravenor907.ravendisk.core.util.Guid
import io.github.ravenor907.ravendisk.core.util.le
import java.nio.ByteBuffer

/**
 * An ext4 formatter in the app itself, so Linux drives can be made without root or bundled tools.
 *
 * Layout matches what `mke2fs -t ext4` makes for a plain data volume, minus the optional extras:
 * 4 KiB blocks, 32768-block groups, 256-byte inodes (one per 16 KiB), sparse superblock backups,
 * extents, a 64 MiB-or-smaller journal, and uninit_bg so only group 0's tables are written; the
 * kernel zeroes the remaining inode tables in the background on first mount, as it does after
 * mke2fs. Checked in CI with e2fsck and by mounting it.
 */
object Ext4 {
    private const val BS = 4096
    private const val BPG = 32768 // blocks per group (one bitmap block)
    private const val INODE_SIZE = 256
    private const val INODE_RATIO = 16384
    private const val FIRST_INO = 11
    private const val ROOT_INO = 2
    private const val JOURNAL_INO = 8
    private const val LOST_FOUND_INO = 11

    // Feature flags.
    private const val COMPAT_HAS_JOURNAL = 0x4
    private const val COMPAT_EXT_ATTR = 0x8
    private const val INCOMPAT_FILETYPE = 0x2
    private const val INCOMPAT_EXTENTS = 0x40
    private const val RO_SPARSE_SUPER = 0x1
    private const val RO_LARGE_FILE = 0x2
    private const val RO_HUGE_FILE = 0x8
    private const val RO_GDT_CSUM = 0x10
    private const val RO_DIR_NLINK = 0x20
    private const val RO_EXTRA_ISIZE = 0x40

    private const val BG_INODE_UNINIT = 0x1
    private const val BG_BLOCK_UNINIT = 0x2
    private const val BG_INODE_ZEROED = 0x4

    data class Layout(
        val blocks: Long,
        val groups: Int,
        val inodesPerGroup: Int,
        val inodeTableBlocks: Int,
        val gdtBlocks: Int,
        val journalBlocks: Int,
    ) {
        val inodes: Long get() = groups.toLong() * inodesPerGroup
        fun groupStart(g: Int) = g.toLong() * BPG
        fun groupBlocks(g: Int) = if (g == groups - 1) (blocks - groupStart(g)).toInt() else BPG
        fun hasSuper(g: Int) = g == 0 || g == 1 || isPower(g, 3) || isPower(g, 5) || isPower(g, 7)

        /** Blocks a group spends on superblock+GDT backup, bitmaps and inode table. */
        fun overhead(g: Int) = (if (hasSuper(g)) 1 + gdtBlocks else 0) + 2 + inodeTableBlocks
        fun blockBitmap(g: Int) = groupStart(g) + (if (hasSuper(g)) 1 + gdtBlocks else 0)
        fun inodeBitmap(g: Int) = blockBitmap(g) + 1
        fun inodeTable(g: Int) = blockBitmap(g) + 2
        fun firstData(g: Int) = inodeTable(g) + inodeTableBlocks
    }

    private fun isPower(n: Int, b: Int): Boolean {
        var x = b
        while (x < n) x *= b
        return x == n
    }

    /** Journal size the way mke2fs picks it, capped to what fits in one group. */
    private fun journalFor(blocks: Long): Int = when {
        blocks < 2048 -> 0
        blocks < 32768 -> 1024
        blocks < 262144 -> 4096
        else -> 16384
    }

    fun layout(blockSize: Int, sectors: Long): Layout {
        var blocks = sectors * blockSize / BS
        require(blocks >= 1024) { "The partition is too small for ext4 (needs 4 MiB)" }
        require(blocks <= 0xFFFFFFFFL) { "ext4 here is limited to 16 TiB" }
        while (true) {
            val groups = ((blocks + BPG - 1) / BPG).toInt()
            val perGroupBlocks = minOf(blocks, BPG.toLong())
            // Inodes per group: one per 16 KiB, filling whole inode table blocks, at most 8 per bitmap bit-block.
            var ipg = (perGroupBlocks * BS / INODE_RATIO).toInt()
            ipg = ((ipg + 15) / 16 * 16).coerceIn(16, BS * 8)
            val itb = ipg * INODE_SIZE / BS
            val gdt = (groups * 32 + BS - 1) / BS
            val l = Layout(blocks, groups, ipg, itb, gdt, journalFor(blocks))
            // A last group too small to hold its own metadata (plus a little data) is dropped, as mke2fs does.
            val last = groups - 1
            if (groups > 1 && l.groupBlocks(last) < l.overhead(last) + 50) {
                blocks = l.groupStart(last)
                continue
            }
            require(l.groupBlocks(0) > l.overhead(0) + 16) { "The partition is too small for ext4" }
            return l
        }
    }

    private fun writtenBitmaps(l: Layout, jg: Int) = setOf(0, jg, l.groups - 1)

    /** Where the journal lives: a group without a superblock backup if there is one (group 2), else group 0. */
    private fun journalGroup(l: Layout): Int = if (l.groups > 2 && l.groupBlocks(2) - l.overhead(2) >= l.journalBlocks) 2 else 0

    fun format(
        dev: BlockDevice,
        firstLba: Long,
        sectors: Long,
        label: String,
        uuid: Guid = Guid.random(),
        progress: ProgressFn = { _, _ -> },
        now: Long = System.currentTimeMillis() / 1000,
    ): Layout {
        require(label.toByteArray(Charsets.UTF_8).size <= 16) { "ext4 labels are at most 16 bytes" }
        val l = layout(dev.blockSize, sectors)
        val base = firstLba * dev.blockSize
        fun put(block: Long, data: ByteArray) {
            require(data.size % BS == 0)
            val off = base + block * BS
            require(off % dev.blockSize == 0L)
            dev.write(off / dev.blockSize, data.size / dev.blockSize, data)
        }

        val jg = journalGroup(l)
        val rootBlock = l.firstData(0)
        val lfBlocks = 4
        val lfBlock = rootBlock + 1
        val group0Used = 1 + lfBlocks // root dir + lost+found
        val journalStart = if (l.journalBlocks == 0) 0L else if (jg == 0) lfBlock + lfBlocks else l.firstData(jg)
        require(l.journalBlocks == 0 || journalStart + l.journalBlocks <= l.groupStart(jg) + l.groupBlocks(jg)) { "The journal doesn't fit" }

        // Per-group usage.
        val usedBlocks = IntArray(l.groups) { g -> l.overhead(g) }
        usedBlocks[0] += group0Used
        if (l.journalBlocks > 0) usedBlocks[jg] += l.journalBlocks
        val freeBlocksTotal = (0 until l.groups).sumOf { (l.groupBlocks(it) - usedBlocks[it]).toLong() }
        val usedInodes0 = FIRST_INO // inodes 1-11 (11 = lost+found)
        val freeInodesTotal = l.inodes - usedInodes0
        val total = 6L + l.inodeTableBlocks + l.journalBlocks + l.groups
        var done = 0L
        fun tick(n: Long) {
            done += n
            progress(done, total)
        }

        // Clear old signatures at the start (an old superblock at 1 KiB, old boot sectors).
        dev.zero(firstLba, minOf(sectors, (64L * 1024) / dev.blockSize))

        // Group descriptors.
        val gdt = ByteArray(l.gdtBlocks * BS)
        val gb = gdt.le()
        val uuidBytes = uuidRaw(uuid)
        for (g in 0 until l.groups) {
            val o = g * 32
            gb.putInt(o, l.blockBitmap(g).toInt())
            gb.putInt(o + 4, l.inodeBitmap(g).toInt())
            gb.putInt(o + 8, l.inodeTable(g).toInt())
            gb.putShort(o + 12, (l.groupBlocks(g) - usedBlocks[g]).toShort())
            val freeInodes = if (g == 0) l.inodesPerGroup - usedInodes0 else l.inodesPerGroup
            gb.putShort(o + 14, freeInodes.toShort())
            gb.putShort(o + 16, (if (g == 0) 2 else 0).toShort()) // root and lost+found
            var flags = 0
            if (g != 0) flags = flags or BG_INODE_UNINIT
            // Block bitmaps are written for group 0 and the journal's group; others are computed from metadata.
            if (g !in writtenBitmaps(l, jg)) flags = flags or BG_BLOCK_UNINIT
            if (g == 0) flags = flags or BG_INODE_ZEROED
            gb.putShort(o + 18, flags.toShort())
            gb.putShort(o + 28, (if (g == 0) l.inodesPerGroup - usedInodes0 else l.inodesPerGroup).toShort()) // itable_unused
            gb.putShort(o + 30, groupChecksum(uuidBytes, g, gdt, o).toShort())
        }

        // Block bitmaps for group 0, the journal's group and the last group (e2fsck wants the last
        // one initialized); the rest are BLOCK_UNINIT and computed from the layout. Inode bitmap for group 0.
        for (g in writtenBitmaps(l, jg)) {
            val bm = ByteArray(BS)
            for (i in 0 until usedBlocks[g]) setBit(bm, i)
            // Bits past the end of a short last group are set (they don't exist).
            for (i in l.groupBlocks(g) until BS * 8) setBit(bm, i)
            put(l.blockBitmap(g), bm)
            tick(1)
        }
        val ib = ByteArray(BS)
        for (i in 0 until usedInodes0) setBit(ib, i)
        for (i in l.inodesPerGroup until BS * 8) setBit(ib, i)
        put(l.inodeBitmap(0), ib)
        tick(1)

        // Group 0's inode table: zeroed, then the root, lost+found and journal inodes.
        val table = ByteArray(l.inodeTableBlocks * BS)
        fun inode(ino: Int, mode: Int, links: Int, size: Long, start: Long, count: Int) {
            val o = (ino - 1) * INODE_SIZE
            val b = table.le()
            b.putShort(o, mode.toShort())
            b.putInt(o + 4, size.toInt())
            b.putInt(o + 8, now.toInt()) // atime
            b.putInt(o + 12, now.toInt()) // ctime
            b.putInt(o + 16, now.toInt()) // mtime
            b.putShort(o + 26, links.toShort())
            b.putInt(o + 28, (count.toLong() * (BS / 512)).toInt()) // i_blocks in 512-byte units
            b.putInt(o + 32, 0x80000) // EXT4_EXTENTS_FL
            // Extent tree in i_block: header + one leaf extent per 32768 blocks.
            b.putShort(o + 40, 0xF30A.toShort())
            val extents = (count + 32767) / 32768
            b.putShort(o + 42, extents.toShort())
            b.putShort(o + 44, 4)
            b.putShort(o + 46, 0)
            for (e in 0 until extents) {
                val eo = o + 52 + e * 12
                val len = minOf(32768, count - e * 32768)
                b.putInt(eo, e * 32768)
                b.putShort(eo + 4, len.toShort())
                val s = start + e * 32768L
                b.putShort(eo + 6, (s ushr 32).toShort())
                b.putInt(eo + 8, s.toInt())
            }
            b.putInt(o + 108, (size ushr 32).toInt())
            b.putShort(o + 128, 32) // i_extra_isize
            b.putInt(o + 144, now.toInt()) // crtime
        }
        inode(ROOT_INO, 0x41ED, 3, BS.toLong(), rootBlock, 1)
        inode(LOST_FOUND_INO, 0x41C0, 2, lfBlocks.toLong() * BS, lfBlock, lfBlocks)
        if (l.journalBlocks > 0) inode(JOURNAL_INO, 0x8180, 1, l.journalBlocks.toLong() * BS, journalStart, l.journalBlocks)
        put(l.inodeTable(0), table)
        tick(l.inodeTableBlocks.toLong())

        // Root directory and lost+found.
        val root = ByteArray(BS)
        var at = dirent(root, 0, ROOT_INO, ".", 2, 12)
        at = dirent(root, at, ROOT_INO, "..", 2, 12)
        dirent(root, at, LOST_FOUND_INO, "lost+found", 2, BS - at)
        put(rootBlock, root)
        val lf = ByteArray(lfBlocks * BS)
        at = dirent(lf, 0, LOST_FOUND_INO, ".", 2, 12)
        dirent(lf, at, ROOT_INO, "..", 2, BS - at)
        for (k in 1 until lfBlocks) lf.le().putShort(k * BS + 4, BS.toShort()) // empty blocks: one unused entry
        put(lfBlock, lf)
        tick(2)

        // Journal: zeroed, with a jbd2 v2 superblock in its first block.
        if (l.journalBlocks > 0) {
            val chunk = 256
            val zeros = ByteArray(chunk * BS)
            var j = 0
            while (j < l.journalBlocks) {
                val n = minOf(chunk, l.journalBlocks - j)
                put(journalStart + j, if (n == chunk) zeros else ByteArray(n * BS))
                j += n
                tick(n.toLong())
            }
            val js = ByteBuffer.allocate(BS) // big-endian, as jbd2 is
            js.putInt(0, 0xC03B3998.toInt())
            js.putInt(4, 4) // superblock v2
            js.putInt(8, 0)
            js.putInt(12, BS)
            js.putInt(16, l.journalBlocks)
            js.putInt(20, 1) // first log block
            js.putInt(24, 1) // sequence
            js.putInt(28, 0) // start: 0 = clean, nothing to replay
            uuidBytes.copyInto(js.array(), 48)
            js.putInt(64, 1) // nr_users
            uuidBytes.copyInto(js.array(), 256) // users[0]: this filesystem
            put(journalStart, js.array())
        }

        // Superblock and its backups.
        val sb = superblock(l, uuidBytes, label, freeBlocksTotal, freeInodesTotal, now, journalStart)
        for (g in 0 until l.groups) {
            if (!l.hasSuper(g)) continue
            val s = sb.copyOf()
            s.le().putShort(0x5A, g.toShort()) // s_block_group_nr
            val blk = ByteArray(BS)
            if (g == 0) s.copyInto(blk, 1024) else s.copyInto(blk, 0)
            if (g == 0) {
                put(0, blk)
            } else {
                put(l.groupStart(g), blk)
            }
            put(l.groupStart(g) + 1, gdt)
            tick(1)
        }
        dev.sync()
        return l
    }

    private fun superblock(l: Layout, uuid: ByteArray, label: String, freeBlocks: Long, freeInodes: Long, now: Long, journalStart: Long): ByteArray {
        val s = ByteArray(1024)
        val b = s.le()
        b.putInt(0x00, l.inodes.toInt())
        b.putInt(0x04, l.blocks.toInt())
        b.putInt(0x08, (l.blocks / 20).toInt()) // 5% reserved for root
        b.putInt(0x0C, freeBlocks.toInt())
        b.putInt(0x10, freeInodes.toInt())
        b.putInt(0x14, 0) // first data block (0 with 4 KiB blocks)
        b.putInt(0x18, 2) // log block size: 1024 << 2
        b.putInt(0x1C, 2) // log cluster size
        b.putInt(0x20, BPG)
        b.putInt(0x24, BPG) // clusters per group
        b.putInt(0x28, l.inodesPerGroup)
        b.putInt(0x2C, 0) // mtime
        b.putInt(0x30, now.toInt()) // wtime
        b.putShort(0x34, 0) // mount count
        b.putShort(0x36, (-1).toShort()) // max mount count: no forced checks
        b.putShort(0x38, 0xEF53.toShort())
        b.putShort(0x3A, 1) // state: clean
        b.putShort(0x3C, 1) // errors: continue
        b.putShort(0x3E, 0)
        b.putInt(0x40, now.toInt()) // last check
        b.putInt(0x44, 0) // check interval
        b.putInt(0x48, 0) // creator OS: Linux
        b.putInt(0x4C, 1) // dynamic revision
        b.putInt(0x54, FIRST_INO)
        b.putShort(0x58, INODE_SIZE.toShort())
        var compat = COMPAT_EXT_ATTR
        if (l.journalBlocks > 0) compat = compat or COMPAT_HAS_JOURNAL
        b.putInt(0x5C, compat)
        b.putInt(0x60, INCOMPAT_FILETYPE or INCOMPAT_EXTENTS)
        b.putInt(0x64, RO_SPARSE_SUPER or RO_LARGE_FILE or RO_HUGE_FILE or RO_GDT_CSUM or RO_DIR_NLINK or RO_EXTRA_ISIZE)
        uuid.copyInto(s, 0x68)
        label.toByteArray(Charsets.UTF_8).copyInto(s, 0x78)
        if (l.journalBlocks > 0) {
            b.putInt(0xE0, JOURNAL_INO)
            // s_jnl_blocks: a copy of the journal inode's i_block (extent header + extents), then sizes.
            b.putShort(0x10C, 0xF30A.toShort())
            b.putShort(0x10E, 1)
            b.putShort(0x110, 4)
            b.putShort(0x112, 0)
            b.putInt(0x118, 0)
            b.putShort(0x11C, l.journalBlocks.toShort())
            b.putShort(0x11E, (journalStart ushr 32).toShort())
            b.putInt(0x120, journalStart.toInt())
            b.putInt(0x10C + 15 * 4, 0) // i_size_high
            b.putInt(0x10C + 16 * 4, l.journalBlocks * BS) // i_size
            s[0xFD] = 1 // s_jnl_backup_type: EXT3_JNL_BACKUP_BLOCKS
        }
        // Directory hashing seed (used once directories grow an index).
        val seed = Guid.random()
        uuidRaw(seed).copyInto(s, 0xEC)
        s[0xFC] = 1 // default hash: half_md4
        b.putShort(0x15C, 32) // min_extra_isize
        b.putShort(0x15E, 32) // want_extra_isize
        b.putInt(0x108, now.toInt()) // mkfs time
        return s
    }

    private fun dirent(b: ByteArray, at: Int, ino: Int, name: String, type: Int, recLen: Int): Int {
        val n = name.toByteArray(Charsets.UTF_8)
        val bb = b.le()
        bb.putInt(at, ino)
        bb.putShort(at + 4, recLen.toShort())
        b[at + 6] = n.size.toByte()
        b[at + 7] = type.toByte()
        n.copyInto(b, at + 8)
        return at + recLen
    }

    private fun setBit(b: ByteArray, i: Int) {
        b[i ushr 3] = (b[i ushr 3].toInt() or (1 shl (i and 7))).toByte()
    }

    private fun uuidRaw(g: Guid): ByteArray {
        val h = g.text.replace("-", "")
        return ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** uninit_bg group descriptor checksum: CRC16 (ANSI, reflected) over uuid, group number and the descriptor up to the checksum. */
    private fun groupChecksum(uuid: ByteArray, group: Int, gdt: ByteArray, off: Int): Int {
        var crc = crc16(0xFFFF, uuid, 0, 16)
        val g = ByteArray(4).also { it.le().putInt(0, group) }
        crc = crc16(crc, g, 0, 4)
        crc = crc16(crc, gdt, off, 30)
        return crc
    }

    private fun crc16(start: Int, b: ByteArray, off: Int, len: Int): Int {
        var crc = start
        for (i in off until off + len) {
            crc = crc xor (b[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc and 0xFFFF
    }
}
