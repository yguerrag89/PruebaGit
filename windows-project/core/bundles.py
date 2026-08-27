from __future__ import annotations
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile
import ctypes
import os
import tempfile


def unpack_upload(name: str, data: bytes):
    lower = name.lower()
    if lower.endswith('.xlsx'):
        return [(name, data)]
    if lower.endswith('.zip'):
        out=[]
        with ZipFile(BytesIO(data)) as z:
            for m in z.namelist():
                if m.lower().endswith('.xlsx') and not Path(m).name.startswith('~$'):
                    out.append((Path(m).name, z.read(m)))
        return out
    if lower.endswith('.rar'):
        return _unpack_rar_libarchive(name, data)
    return []


def _unpack_rar_libarchive(name: str, data: bytes):
    """Soporte RAR opcional en Linux/macOS si libarchive está disponible."""
    lib = None
    for candidate in ('libarchive.so', 'libarchive.so.13', 'libarchive.dylib'):
        try:
            lib = ctypes.CDLL(candidate); break
        except OSError:
            pass
    if lib is None:
        raise RuntimeError('RAR requiere libarchive en el servidor. Puede subir los XLSX o un ZIP.')

    lib.archive_read_new.restype=ctypes.c_void_p
    lib.archive_read_support_filter_all.argtypes=[ctypes.c_void_p]
    lib.archive_read_support_format_all.argtypes=[ctypes.c_void_p]
    lib.archive_read_open_filename.argtypes=[ctypes.c_void_p,ctypes.c_char_p,ctypes.c_size_t]
    lib.archive_read_open_filename.restype=ctypes.c_int
    lib.archive_read_next_header.argtypes=[ctypes.c_void_p,ctypes.POINTER(ctypes.c_void_p)]
    lib.archive_read_next_header.restype=ctypes.c_int
    lib.archive_entry_pathname.argtypes=[ctypes.c_void_p]
    lib.archive_entry_pathname.restype=ctypes.c_char_p
    lib.archive_read_data.argtypes=[ctypes.c_void_p,ctypes.c_void_p,ctypes.c_size_t]
    lib.archive_read_data.restype=ctypes.c_ssize_t
    lib.archive_read_free.argtypes=[ctypes.c_void_p]
    lib.archive_error_string.argtypes=[ctypes.c_void_p]
    lib.archive_error_string.restype=ctypes.c_char_p

    with tempfile.TemporaryDirectory() as td:
        src=Path(td)/'upload.rar'; src.write_bytes(data)
        a=lib.archive_read_new(); lib.archive_read_support_filter_all(a); lib.archive_read_support_format_all(a)
        if lib.archive_read_open_filename(a, str(src).encode(), 10240) != 0:
            err=lib.archive_error_string(a); raise RuntimeError(err.decode() if err else 'No se pudo abrir RAR')
        out=[]; entry=ctypes.c_void_p()
        try:
            while True:
                r=lib.archive_read_next_header(a,ctypes.byref(entry))
                if r==1: break
                if r!=0: break
                raw=lib.archive_entry_pathname(entry)
                member=raw.decode('utf-8','replace') if raw else ''
                if not member.lower().endswith('.xlsx') or Path(member).name.startswith('~$'):
                    # consumir datos aunque no se guarden
                    buf=ctypes.create_string_buffer(65536)
                    while lib.archive_read_data(a,buf,len(buf))>0: pass
                    continue
                chunks=[]; buf=ctypes.create_string_buffer(1024*1024)
                while True:
                    n=lib.archive_read_data(a,buf,len(buf))
                    if n==0: break
                    if n<0: raise RuntimeError('Error leyendo archivo dentro del RAR')
                    chunks.append(buf.raw[:n])
                out.append((Path(member).name,b''.join(chunks)))
        finally:
            lib.archive_read_free(a)
        return out
