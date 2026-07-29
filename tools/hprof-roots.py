#!/usr/bin/env python3
"""
Focused hprof reader: find the reference chain from a GC root to a target object.

Not a general analyzer. It builds only what is needed to answer "what is keeping this
classloader alive": object -> outbound references, object -> class name, and the set of GC roots.
Primitive arrays are skipped entirely, which is most of the heap by volume.
"""
import struct, sys, collections

TAG_STRING, TAG_LOAD_CLASS, TAG_HEAP_DUMP, TAG_HEAP_DUMP_SEG = 0x01, 0x02, 0x0C, 0x1C

# Sub-record tags inside a heap dump segment, with the bytes that follow the object ID.
# Getting these wrong desyncs the whole stream, which surfaces as a bogus tag much later.
# 'I' means one ID-sized field.
ROOT_TAGS = {
    0xFF: ('unknown', 0),
    0x01: ('jni-global', 'I'),      # + JNI global ref ID
    0x02: ('jni-local', 8),         # + thread serial, frame number
    0x03: ('java-frame', 8),
    0x04: ('native-stack', 4),      # + thread serial
    0x05: ('sticky-class', 0),
    0x06: ('thread-block', 4),
    0x07: ('monitor-used', 0),
    0x08: ('thread-object', 8),
    0x89: ('interned-string', 0),
    0x8A: ('finalizing', 0),
    0x8B: ('debugger', 0),
    0x8C: ('reference-cleanup', 0),
    0x8D: ('vm-internal', 0),
    0x8E: ('jni-monitor', 8),
    0xFE: ('unreachable', 0),
}
CLASS_DUMP, INSTANCE_DUMP, OBJ_ARRAY, PRIM_ARRAY = 0x20, 0x21, 0x22, 0x23

BASIC_SIZE = {2: None, 4: 1, 5: 2, 6: 4, 7: 8, 8: 1, 9: 2, 10: 4, 11: 8}  # 2 = object


class Heap:
    def __init__(self, path):
        self.f = open(path, 'rb')
        self.strings = {}
        self.class_name_id = {}      # class object id -> name string id
        self.class_of = {}           # object id -> class object id
        self.refs = collections.defaultdict(list)   # object id -> [(field_name, target_id)]
        self.roots = {}              # object id -> root kind
        self.array_class = {}
        self._parse()

    def _read(self, n):
        b = self.f.read(n)
        if len(b) != n:
            raise EOFError
        return b

    def _id(self):
        return struct.unpack('>Q', self._read(8))[0] if self.idsize == 8 else \
               struct.unpack('>I', self._read(4))[0]

    def _parse(self):
        while self.f.read(1) != b'\0':
            pass
        self.idsize = struct.unpack('>I', self._read(4))[0]
        self._read(8)
        while True:
            head = self.f.read(9)
            if len(head) < 9:
                break
            tag, _ts, length = struct.unpack('>BII', head)
            if tag == TAG_STRING:
                sid = self._id()
                self.strings[sid] = self._read(length - self.idsize).decode('utf-8', 'replace')
            elif tag == TAG_LOAD_CLASS:
                self._read(4)
                cid = self._id()
                self._read(4)
                self.class_name_id[cid] = self._id()
            elif tag in (TAG_HEAP_DUMP, TAG_HEAP_DUMP_SEG):
                self._segment(length)
            else:
                self.f.seek(length, 1)

    def _segment(self, length):
        end = self.f.tell() + length
        while self.f.tell() < end:
            sub = self._read(1)[0]
            if sub in ROOT_TAGS:
                kind, extra = ROOT_TAGS[sub]
                oid = self._id()
                if extra == 'I':
                    self._id()
                elif extra:
                    self._read(extra)
                self.roots.setdefault(oid, kind)
            elif sub == CLASS_DUMP:
                self._class_dump()
            elif sub == INSTANCE_DUMP:
                self._instance_dump()
            elif sub == OBJ_ARRAY:
                self._obj_array()
            elif sub == PRIM_ARRAY:
                self._prim_array()
            else:
                raise ValueError('unknown sub-record 0x%02x' % sub)

    def _class_dump(self):
        cid = self._id()
        self._read(4)
        superid = self._id()
        for _ in range(5):
            self._id()
        self._read(4)  # instance size is u4, not u8
        cpool = struct.unpack('>H', self._read(2))[0]
        for _ in range(cpool):
            self._read(2)
            t = self._read(1)[0]
            self._skip_value(t)
        statics = struct.unpack('>H', self._read(2))[0]
        self.static_fields = getattr(self, 'static_fields', {})
        for _ in range(statics):
            nid = self._id()
            t = self._read(1)[0]
            if t == 2:
                tgt = self._id()
                if tgt:
                    self.refs[cid].append((self.strings.get(nid, '?'), tgt))
            else:
                self._skip_value(t)
        inst = struct.unpack('>H', self._read(2))[0]
        fields = []
        for _ in range(inst):
            nid = self._id()
            t = self._read(1)[0]
            fields.append((self.strings.get(nid, '?'), t))
        self.inst_fields = getattr(self, 'inst_fields', {})
        self.inst_fields[cid] = (superid, fields)
        self.class_of[cid] = cid

    def _skip_value(self, t):
        if t == 2:
            self._id()
        else:
            self._read(BASIC_SIZE[t])

    def _instance_dump(self):
        oid = self._id()
        self._read(4)
        cid = self._id()
        nbytes = struct.unpack('>I', self._read(4))[0]
        data = self._read(nbytes)
        self.class_of[oid] = cid
        pos, c = 0, cid
        out = []
        while c and c in self.inst_fields:
            superid, fields = self.inst_fields[c]
            for name, t in fields:
                if t == 2:
                    if pos + self.idsize > len(data):
                        break
                    tgt = struct.unpack('>Q' if self.idsize == 8 else '>I',
                                        data[pos:pos + self.idsize])[0]
                    if tgt:
                        out.append((name, tgt))
                    pos += self.idsize
                else:
                    pos += BASIC_SIZE[t]
            c = superid
        if out:
            self.refs[oid] = out

    def _obj_array(self):
        oid = self._id()
        self._read(4)
        n = struct.unpack('>I', self._read(4))[0]
        cid = self._id()
        self.class_of[oid] = cid
        out = []
        for i in range(n):
            tgt = self._id()
            if tgt:
                out.append(('[%d]' % i, tgt))
        if out:
            self.refs[oid] = out

    def _prim_array(self):
        self._id()
        self._read(4)
        n = struct.unpack('>I', self._read(4))[0]
        t = self._read(1)[0]
        self.f.seek(n * BASIC_SIZE[t], 1)

    def name_of(self, oid):
        cid = self.class_of.get(oid)
        if cid is None:
            return '?'
        return self.strings.get(self.class_name_id.get(cid, 0), '?').replace('/', '.')


def main(path, target_substr):
    h = Heap(path)
    print('parsed: %d objects with refs, %d roots, %d classes' %
          (len(h.refs), len(h.roots), len(h.class_name_id)), file=sys.stderr)

    targets = [o for o in h.class_of if target_substr in h.name_of(o)]
    print('target objects matching %r: %d' % (target_substr, len(targets)), file=sys.stderr)
    if not targets:
        return

    # Reverse edges, then BFS from every target back towards a GC root.
    back = collections.defaultdict(list)
    for src, lst in h.refs.items():
        for fname, tgt in lst:
            back[tgt].append((src, fname))

    for t in targets[:3]:
        print('\n=== chain to %s @%x ===' % (h.name_of(t), t))
        seen, queue, parent = {t}, collections.deque([t]), {}
        found = None
        while queue:
            cur = queue.popleft()
            if cur in h.roots and cur != t:
                found = cur
                break
            for src, fname in back.get(cur, []):
                if src not in seen:
                    seen.add(src)
                    parent[src] = (cur, fname)
                    queue.append(src)
        if found is None:
            print('  (no GC root path found - only weakly reachable)')
            continue
        chain, node = [], found
        while node != t:
            nxt, fname = parent[node]
            chain.append('  %s @%x  --%s-->' % (h.name_of(node), node, fname))
            node = nxt
        print('  GC ROOT (%s)' % h.roots[found])
        for line in chain:
            print(line)
        print('  %s @%x' % (h.name_of(t), t))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
