package com.vidrio.player;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MusicLibPlugin.class);
        super.onCreate(savedInstanceState);
        pantallaCompleta();
    }

    /**
     * La app se dibuja debajo de la barra de notificaciones y de la barra de gestos,
     * sin los bordes negros. Los tamaños de esas barras se le pasan a la página (variables
     * --sat, --sab, --sal y --sar) para que no tape los botones.
     */
    private void pantallaCompleta() {
        Window w = getWindow();
        WindowCompat.setDecorFitsSystemWindows(w, false);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        View root = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            float d = getResources().getDisplayMetrics().density;
            MusicLibPlugin.INSETS = new float[] {
                Math.round(bars.top / d * 10f) / 10f,
                Math.round(bars.right / d * 10f) / 10f,
                Math.round(Math.max(bars.bottom, ime.bottom) / d * 10f) / 10f,
                Math.round(bars.left / d * 10f) / 10f
            };
            enviarInsets();
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void enviarInsets() {
        if (getBridge() == null) return;
        final WebView web = getBridge().getWebView();
        if (web == null) return;
        float[] i = MusicLibPlugin.INSETS;
        final String js = "window.__vidrioInsets&&window.__vidrioInsets({t:" + i[0] + ",r:" + i[1] + ",b:" + i[2] + ",l:" + i[3] + "})";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    // Con la pantalla apagada o la app en segundo plano la página tiene que seguir
    // ejecutando su código (pasar a la siguiente canción, atender los botones de la notificación).
    private void mantenerViva() {
        try {
            if (getBridge() != null && getBridge().getWebView() != null) {
                getBridge().getWebView().resumeTimers();
            }
        } catch (Exception e) {
            // nada
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mantenerViva();
    }

    @Override
    public void onStop() {
        super.onStop();
        mantenerViva();
    }
}
