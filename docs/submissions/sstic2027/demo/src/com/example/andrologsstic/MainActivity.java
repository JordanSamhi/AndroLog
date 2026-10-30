package com.example.andrologsstic;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

/** Training app: display a local decision, never visit the supplied URL. */
public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Uri input = getIntent().getData();
        String raw = input == null ? " https://trusted.example/account " : input.getQueryParameter("url");
        boolean lockdown = input != null && "deny".equals(input.getQueryParameter("policy"));
        String decision = new UrlAudit().inspect(raw, lockdown);
        TextView view = new TextView(this);
        view.setPadding(32, 72, 32, 32);
        view.setTextSize(21);
        view.setText("AndroLog · démo SSTIC\n\nEntrée :\n" + raw
                + "\n\nPolitique : " + (lockdown ? "tout refuser" : "hôte autorisé")
                + "\n\n" + decision + "\n\nDémonstration pédagogique.\nAucune requête réseau, aucun fichier ouvert.");
        setContentView(view);
    }
}
