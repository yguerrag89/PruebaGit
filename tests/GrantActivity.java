package com.ilubox.movimientosq9.tests;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Permiso sobre una carpeta ficticia, concedido por su propio proveedor. */
public final class GrantActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        grantUriPermission("com.ilubox.movimientosq9",
            Uri.parse("content://com.ilubox.movimientosq9.tests.documents/tree/root"),
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        finish();
    }
}
