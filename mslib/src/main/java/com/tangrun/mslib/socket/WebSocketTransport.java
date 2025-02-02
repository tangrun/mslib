package com.tangrun.mslib.socket;

import android.os.Handler;
import android.os.HandlerThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import okhttp3.*;
import okhttp3.logging.HttpLoggingInterceptor;
import okio.ByteString;
import org.json.JSONObject;
import org.mediasoup.droid.Logger;
import org.protoojs.droid.Message;
import org.protoojs.droid.transports.AbsWebSocketTransport;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.util.concurrent.CountDownLatch;

import static org.apache.http.conn.ssl.SSLSocketFactory.SSL;

public class WebSocketTransport extends AbsWebSocketTransport {

    // Log tag.
    private static final String TAG = "MS_WebSocketTransport";
    // Closed flag.
    private boolean mClosed;
    // Connected flag.
    private boolean mConnected;
    // OKHttpClient.
    private final OkHttpClient mOkHttpClient;
    // Handler associate to current thread.
    private final Handler mHandler;
    // Retry operation.
    private final RetryStrategy mRetryStrategy;
    // WebSocket instance.
    private WebSocket mWebSocket;
    // Listener.
    private Listener mListener;

    private static class RetryStrategy {

        private final int retries;
        private final int factor;
        private final int minTimeout;
        private final int maxTimeout;

        private int retryCount = 1;

        RetryStrategy(int retries, int factor, int minTimeout, int maxTimeout) {
            this.retries = retries;
            this.factor = factor;
            this.minTimeout = minTimeout;
            this.maxTimeout = maxTimeout;
        }

        void retried() {
            retryCount++;
        }

        int getReconnectInterval() {
            if (retryCount > retries) {
                return -1;
            }
            int reconnectInterval = (int) (minTimeout * Math.pow(factor, retryCount));
            reconnectInterval = Math.min(reconnectInterval, maxTimeout);
            return reconnectInterval;
        }

        void reset() {
            if (retryCount != 0) {
                retryCount = 0;
            }
        }
    }

    public WebSocketTransport(String url) {
        super(url);
        mOkHttpClient = getUnsafeOkHttpClient();
        HandlerThread handlerThread = new HandlerThread("socket");
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
        mRetryStrategy = new RetryStrategy(10, 2, 1000, 8 * 1000);
    }

    @Override
    public void connect(Listener listener) {
        Logger.d(TAG, "connect()");
        mListener = listener;
        mHandler.post(this::newWebSocket);
    }

    Request request;

    private void newWebSocket() {
        mWebSocket = null;
        if (request == null)
            request = new Request.Builder().url(mUrl).addHeader("Sec-WebSocket-Protocol", "protoo").build();
        mOkHttpClient.newWebSocket(
                request,
                new ProtooWebSocketListener());
    }

    private boolean scheduleReconnect1() {
        int reconnectInterval = mRetryStrategy.getReconnectInterval();
        if (reconnectInterval == -1) {
            return false;
        }
        return true;
    }

    private boolean scheduleReconnect() {
        int reconnectInterval = mRetryStrategy.getReconnectInterval();
        if (reconnectInterval == -1) {
            return false;
        }
        Logger.d(TAG, "scheduleReconnect() ");
        mHandler.postDelayed(
                () -> {
                    if (mClosed) {
                        return;
                    }
                    Logger.w(TAG, "doing reconnect job, retryCount: " + mRetryStrategy.retryCount);
                    mOkHttpClient.dispatcher().cancelAll();
                    newWebSocket();
                    mRetryStrategy.retried();
                },
                reconnectInterval);
        return true;
    }

    @Override
    public String sendMessage(JSONObject message) {
        if (mClosed) {
            throw new IllegalStateException("transport closed");
        }
        String payload = message.toString();
        mHandler.post(
                () -> {
                    if (mClosed) {
                        return;
                    }
                    if (mWebSocket != null) {
                        mWebSocket.send(payload);
                    }
                });
        return payload;
    }

    @Override
    public void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        Logger.d(TAG, "close()");
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        mHandler.post(
                () -> {
                    if (mWebSocket != null) {
                        mWebSocket.close(1000, "bye");
                        mWebSocket = null;
                    }
                    countDownLatch.countDown();
                });
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean isClosed() {
        return mClosed;
    }

    private class ProtooWebSocketListener extends WebSocketListener {

        @Override
        public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
            if (mClosed) {
                return;
            }
            Logger.d(TAG, "onOpen() " + response.code() + " " + response.message());
            mWebSocket = webSocket;
            mConnected = true;
            if (mListener != null) {
                mListener.onOpen();
            }
            mRetryStrategy.reset();
        }

        @Override
        public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            Logger.w(TAG, "onClosed() " + code + " " + reason);
            if (mClosed) {
                return;
            }
            mClosed = true;
            mConnected = false;
            mRetryStrategy.reset();
            if (mListener != null) {
                mListener.onClose();
            }
        }

        @Override
        public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            Logger.w(TAG, "onClosing() " + code + " " + reason);
        }

        @Override
        public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
            Logger.w(TAG, "onFailure() " + (response == null ? "" : response.code() + " " + response.message()));
            if (mClosed) {
                return;
            }
            if (scheduleReconnect()) {
                if (mListener != null) {
                    if (mConnected) {
                        mListener.onFail();
                    } else {
                        mListener.onDisconnected();
                    }
                }
            } else {
                Logger.e(TAG, "give up reconnect. notify closed");
                mClosed = true;
                if (mListener != null) {
                    mListener.onClose();
                }
                mRetryStrategy.reset();
            }
        }

        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
            Logger.d(TAG, "onMessage() "+text);
            if (mClosed) {
                return;
            }
            Message message = Message.parse(text);
            if (message == null) {
                return;
            }
            if (mListener != null) {
                mListener.onMessage(message);
            }
        }

        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
            Logger.d(TAG, "onMessage()");
        }
    }

    private OkHttpClient getUnsafeOkHttpClient() {
        try {
            final TrustManager[] trustAllCerts =
                    new TrustManager[]{
                            new X509TrustManager() {

                                @Override
                                public void checkClientTrusted(
                                        java.security.cert.X509Certificate[] chain, String authType)
                                        throws CertificateException {
                                }

                                @Override
                                public void checkServerTrusted(
                                        java.security.cert.X509Certificate[] chain, String authType)
                                        throws CertificateException {
                                }

                                // Called reflectively by X509TrustManagerExtensions.
                                public void checkServerTrusted(
                                        java.security.cert.X509Certificate[] chain, String authType, String host) {
                                }

                                @Override
                                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                                    return new java.security.cert.X509Certificate[]{};
                                }
                            }
                    };

            final SSLContext sslContext = SSLContext.getInstance(SSL);
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            HttpLoggingInterceptor httpLoggingInterceptor =
                    new HttpLoggingInterceptor(s -> Logger.d(TAG, s));
            httpLoggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BASIC);

            OkHttpClient.Builder builder =
                    new OkHttpClient.Builder()
                            .addInterceptor(httpLoggingInterceptor)
                            .retryOnConnectionFailure(true);
            builder.sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0]);

            builder.hostnameVerifier((hostname, session) -> true);

            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
