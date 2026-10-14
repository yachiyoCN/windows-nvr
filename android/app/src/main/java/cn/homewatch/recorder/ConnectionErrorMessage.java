package cn.homewatch.recorder;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;

/** Actionable connection errors without copying credentials or raw network errors to the UI. */
final class ConnectionErrorMessage {
    static String describe(Throwable error) {
        boolean tls = false, certificate = false, protocol = false;
        boolean dns = false, refused = false, timeout = false;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof CertificateExpiredException)
                return "录像机证书已过期，请检查两端日期，并在电脑重启录像机后重试";
            if (cause instanceof CertificateNotYetValidException)
                return "录像机证书尚未生效，请检查手机和录像机电脑的完整日期";
            String message = cause.getMessage() == null ? "" : cause.getMessage();
            if (cause instanceof CertificateException && (message.contains("指纹不匹配") || message.contains("PIN_MISMATCH")))
                return "连接指纹与当前服务器不匹配。请从这台录像机电脑重新复制指纹，并核对 9079 转发到的电脑";
            String lower = message.toLowerCase(Locale.ROOT).replace('_', ' ');
            if (lower.contains("拒绝连接未确认的录像机") || lower.contains("连接目标与已确认的录像机不一致"))
                return "连接地址与已确认的录像机不一致，请核对域名和端口，不要使用跳转地址";
            tls |= cause instanceof SSLException;
            certificate |= cause instanceof CertificateException || cause instanceof SSLPeerUnverifiedException;
            protocol |= cause instanceof SSLProtocolException || lower.contains("wrong version number") || lower.contains("not an ssl/tls record") || lower.contains("unrecognized ssl message");
            dns |= cause instanceof UnknownHostException;
            refused |= cause instanceof ConnectException;
            timeout |= cause instanceof SocketTimeoutException;
        }
        if (protocol) return "9079 端口未完成 HTTPS 握手，请确认端口映射到新版录像机的 App API，并检查转发设置";
        if (certificate) return "HTTPS 证书验证失败，请核对这台录像机电脑的连接指纹、证书和两端日期";
        if (tls) return "HTTPS 握手失败，请确认另一台电脑已更新录像机、开启 App API，并核对 9079 端口映射";
        if (dns) return "域名无法解析，请检查域名是否指向当前外网 IP；也可用外网 IP 对比测试";
        if (refused) return "录像机端口拒绝连接，请检查电脑的 App API 是否开启，以及 9079 端口映射和防火墙";
        if (timeout) return "连接录像机超时，请检查网络、9079 端口映射和电脑是否在线";
        return "无法连接录像机，请确认地址、网络和电脑上的 API 服务";
    }
}
