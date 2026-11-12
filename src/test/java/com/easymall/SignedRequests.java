package com.easymall;

import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.util.UUID;

/** Test client for request signing: adds X-Timestamp, X-Nonce and X-Signature to a MockMvc request. */
final class SignedRequests {
    private SignedRequests() {}
    static RequestPostProcessor signed(String signKey) { return signed(signKey,System.currentTimeMillis(),UUID.randomUUID().toString()); }
    static RequestPostProcessor signed(String signKey,long timestamp,String nonce) {
        return request -> {
            byte[] body=request.getContentAsByteArray()==null?new byte[0]:request.getContentAsByteArray();
            String canonical=RequestSignature.canonical(request.getMethod(),request.getRequestURI(),request.getQueryString(),timestamp,nonce,body);
            request.addHeader(RequestSignature.TIMESTAMP,String.valueOf(timestamp));
            request.addHeader(RequestSignature.NONCE,nonce);
            request.addHeader(RequestSignature.SIGNATURE,RequestSignature.sign(signKey,canonical));
            return request;
        };
    }
}
