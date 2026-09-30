import os
import sys

root = sys.argv[1]
lib = os.path.join(root, 'lib')

links = {
    'libz.so.1': 'libz.so.1.3.2',
    'libsqlite3.so': 'libsqlite3.so.0',
    'libcrypto.so': 'libcrypto.so.3',
    'libssl.so': 'libssl.so.3',
    'libcares.so.2': 'libcares.so',
    'libffi.so.8': 'libffi.so',
    'libicui18n.so': 'libicui18n.so.78',
    'libicuuc.so': 'libicuuc.so.78',
    'libicudata.so': 'libicudata.so.78',
}

for a, b in links.items():
    pa = os.path.join(lib, a)
    pb = os.path.join(lib, b)
    if not os.path.exists(pa) and os.path.exists(pb):
        try:
            os.symlink(b, pa)
            print('linked ' + a + ' -> ' + b)
        except Exception as e:
            print('skip ' + a + ': ' + str(e))

need = ['libz.so.1', 'libcares.so', 'libsqlite3.so', 'libffi.so', 'libcrypto.so.3', 'libssl.so.3', 'libicui18n.so.78', 'libicuuc.so.78', 'libc++_shared.so']
missing = [n for n in need if not os.path.exists(os.path.join(lib, n))]
if missing:
    print('MISSING: ' + ', '.join(missing))
    sys.exit(1)
print('ALL LIBS OK')
