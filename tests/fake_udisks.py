"""A fake udisks2 service for tests. Run with a Python that has PyGObject.

It exposes the real udisks method signatures on a private D-Bus, keeps each
partition's files in a store directory, and hands out a NEW mount path on every
mount (like a real remount can). Crucially it rejects, as NotAuthorized, any
call whose options don't include auth.no_user_interaction=true: that is the
call that would pop up a password prompt on a real system.

Env: FAKE_DENY=<Method>  always refuse that method as NotAuthorized
     FAKE_AUTOMOUNT=1    mount new partitions immediately, like a desktop would
     FAKE_SIZE=<bytes>   disk size (default 32 GB)
"""

import json
import os
import sys
import uuid as uuidlib

from gi.repository import Gio, GLib

ROOT = "/org/freedesktop/UDisks2"
DISK = f"{ROOT}/block_devices/sdz"
DRIVE = f"{ROOT}/drives/Fake_Stick"

XML = """
<node>
 <interface name="org.freedesktop.UDisks2.Block">
  <method name="Format"><arg type="s" direction="in"/><arg type="a{sv}" direction="in"/></method>
  <property name="Size" type="t" access="read"/>
  <property name="HintSystem" type="b" access="read"/>
  <property name="Drive" type="o" access="read"/>
  <property name="IdUUID" type="s" access="read"/>
 </interface>
 <interface name="org.freedesktop.UDisks2.PartitionTable">
  <method name="CreatePartitionAndFormat">
   <arg type="t" direction="in"/><arg type="t" direction="in"/><arg type="s" direction="in"/>
   <arg type="s" direction="in"/><arg type="a{sv}" direction="in"/><arg type="s" direction="in"/>
   <arg type="a{sv}" direction="in"/><arg type="o" direction="out"/>
  </method>
 </interface>
 <interface name="org.freedesktop.UDisks2.Partition">
  <property name="Offset" type="t" access="read"/>
  <property name="Size" type="t" access="read"/>
 </interface>
 <interface name="org.freedesktop.UDisks2.Filesystem">
  <method name="Mount"><arg type="a{sv}" direction="in"/><arg type="s" direction="out"/></method>
  <method name="Unmount"><arg type="a{sv}" direction="in"/></method>
  <property name="MountPoints" type="aay" access="read"/>
 </interface>
 <interface name="org.freedesktop.UDisks2.Drive">
  <method name="PowerOff"><arg type="a{sv}" direction="in"/></method>
 </interface>
</node>
"""

base = sys.argv[1]
info = Gio.DBusNodeInfo.new_for_xml(XML)
IFACE = {i.name.rsplit(".", 1)[-1]: i for i in info.interfaces}
state = {"size": int(os.environ.get("FAKE_SIZE", 32_000_000_000)), "parts": {}, "mounts": 0, "regs": {}}
log_path = os.path.join(base, "calls.jsonl")
conn = None


def log(obj, method, **kw):
    with open(log_path, "a") as f:
        f.write(json.dumps({"obj": obj, "method": method, **kw}) + "\n")


def deny(inv, why="Not authorized to perform operation"):
    inv.return_dbus_error("org.freedesktop.UDisks2.Error.NotAuthorizedCanObtain", why)


def allowed(inv, method, options):
    if os.environ.get("FAKE_DENY") == method:
        deny(inv)
        return False
    if options.get("auth.no_user_interaction") is not True:
        deny(inv, "This call could have prompted for a password")
        return False
    return True


def new_mount(name):
    state["mounts"] += 1
    path = os.path.join(base, f"mnt{state['mounts']}-{name}")
    os.symlink(state["parts"][name]["store"], path)
    state["parts"][name]["mounted"] = path
    return path


def register(path, ifaces):
    ids = [conn.register_object(path, IFACE[i], on_call, on_get, None) for i in ifaces]
    state["regs"][path] = ids


def unregister(path):
    for i in state["regs"].pop(path, []):
        conn.unregister_object(i)


def on_call(c, sender, path, iface, method, params, inv):
    args = params.unpack()
    name = path.rsplit("/", 1)[-1]
    if method == "Format":
        if not allowed(inv, method, args[1]):
            return
        log(path, method, table=args[0], options=args[1])
        for pname in list(state["parts"]):
            unregister(f"{ROOT}/block_devices/{pname}")
        state["parts"].clear()
        inv.return_value(None)
    elif method == "CreatePartitionAndFormat":
        offset, size, ptype, pname, opts, fstype, fsopts = args
        if not allowed(inv, method, opts) or not allowed(inv, method, fsopts):
            return
        n = f"sdz{len(state['parts']) + 1}"
        store = os.path.join(base, f"store-{n}")
        os.makedirs(store, exist_ok=True)
        state["parts"][n] = {"offset": offset, "size": size, "type": ptype, "name": pname,
                             "fstype": fstype, "fsopts": fsopts, "store": store, "mounted": None,
                             "uuid": str(uuidlib.uuid4())}
        log(path, method, offset=offset, size=size, type=ptype, fstype=fstype, fsopts=fsopts)
        ppath = f"{ROOT}/block_devices/{n}"
        register(ppath, ["Block", "Partition", "Filesystem"])
        if os.environ.get("FAKE_AUTOMOUNT"):
            new_mount(n)
        inv.return_value(GLib.Variant("(o)", (ppath,)))
    elif method == "Mount":
        if not allowed(inv, method, args[0]):
            return
        p = state["parts"][name]
        if p["mounted"]:
            inv.return_dbus_error("org.freedesktop.UDisks2.Error.AlreadyMounted",
                                  f"Device is already mounted at `{p['mounted']}'")
            return
        mp = new_mount(name)
        log(path, method, at=mp)
        inv.return_value(GLib.Variant("(s)", (mp,)))
    elif method == "Unmount":
        if not allowed(inv, method, args[0]):
            return
        p = state["parts"][name]
        if p["mounted"]:
            os.unlink(p["mounted"])
            p["mounted"] = None
        log(path, method)
        inv.return_value(None)
    elif method == "PowerOff":
        if not allowed(inv, method, args[0]):
            return
        log(path, method)
        inv.return_value(None)
        with open(os.path.join(base, "parts.json"), "w") as f:
            json.dump(state["parts"], f)


def on_get(c, sender, path, iface, prop):
    name = path.rsplit("/", 1)[-1]
    if path == DISK:
        return {"Size": GLib.Variant("t", state["size"]), "HintSystem": GLib.Variant("b", False),
                "Drive": GLib.Variant("o", DRIVE), "IdUUID": GLib.Variant("s", "")}[prop]
    p = state["parts"][name]
    return {
        "Size": GLib.Variant("t", p["size"]), "HintSystem": GLib.Variant("b", False),
        "Drive": GLib.Variant("o", DRIVE), "IdUUID": GLib.Variant("s", p["uuid"]),
        "Offset": GLib.Variant("t", p["offset"]),
        "MountPoints": GLib.Variant("aay", [p["mounted"].encode() + b"\0"] if p["mounted"] else []),
    }[prop]


def on_bus(c, name):
    global conn
    conn = c
    register(DISK, ["Block", "PartitionTable"])
    conn.register_object(DRIVE, IFACE["Drive"], on_call, None, None)


def on_name(c, name):
    print("READY", flush=True)


Gio.bus_own_name(Gio.BusType.SYSTEM, "org.freedesktop.UDisks2", Gio.BusNameOwnerFlags.NONE,
                 on_bus, on_name, lambda *a: sys.exit("lost name"))
GLib.MainLoop().run()
