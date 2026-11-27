package org.shidulab.apparecchio;

import android.content.Context;
import java.net.URL;

final class AppService {
    private AppService() {}

    static URL root(Context context) throws Exception {

        String address = BuildConfig.OCCHIO_SERVICE.trim();
        if (address.isEmpty()) throw new IllegalStateException("Servizio ShiduLab non configurato in questa build.");
        URL url = new URL(address.endsWith("/") ? address : address + "/");
        if (!"https".equals(url.getProtocol()) || url.getUserInfo()!=null || url.getQuery()!=null || url.getRef()!=null)
            throw new IllegalStateException("Il servizio deve avere un indirizzo HTTPS.");
        return url;
    }
}
