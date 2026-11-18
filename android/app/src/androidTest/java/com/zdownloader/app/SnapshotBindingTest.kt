package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File
import java.util.UUID

class SnapshotBindingTest {
    @Test fun androidSnapshotsRejectChangedRenamedReplacedAndCancelledSources() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val home=File(app.cacheDir,"snapshot-binding-${UUID.randomUUID()}").apply {check(!exists()&&mkdirs())}
        val python=app.extractor.python()
        val fixture=python.getModule("types").callAttr("ModuleType","snapshot_binding_fixture")
        fixture.put("home",home.path)
        try {python.builtins.callAttr("exec","""
from pathlib import Path
import hashlib, os
from copy_snapshot import ReadSnapshot, SnapshotError
root = Path(home)
source = root / 'source.mp4'
payload = bytes(range(256)) * 513
for mutation in ('append', 'truncate', 'rewrite', 'rename', 'replace'):
    source.write_bytes(payload)
    with ReadSnapshot(source, len(payload), lambda: None) as snapshot:
        assert snapshot.read() == payload
        assert snapshot.sha256 == hashlib.sha256(payload).hexdigest()
        if mutation == 'append':
            with source.open('ab') as stream: stream.write(b'!')
        elif mutation == 'truncate':
            with source.open('r+b') as stream: stream.truncate(len(payload) - 1)
        elif mutation == 'rewrite':
            with source.open('r+b') as stream: stream.write(b'changed')
        elif mutation == 'rename':
            source.rename(root / 'renamed.mp4')
        else:
            replacement = root / 'replacement.mp4'
            replacement.write_bytes(payload)
            os.replace(replacement, source)
        try:
            snapshot.read()
        except (SnapshotError, FileNotFoundError):
            pass
        else:
            raise AssertionError('Changed Android snapshot accepted: ' + mutation)
source.write_bytes(payload)
cancelled = False
def check():
    if cancelled: raise InterruptedError('cancelled')
with ReadSnapshot(source, len(payload), check) as snapshot:
    snapshot.read()
    cancelled = True
    try: snapshot.read()
    except InterruptedError: pass
    else: raise AssertionError('Cancelled snapshot accepted')
assert source.read_bytes() == payload
""".trimIndent(),fixture.get("__dict__"))}
        finally {check(home.deleteRecursively())}
    }
}
