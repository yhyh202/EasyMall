package com.easymall;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

/** Signing rules against vectors computed independently with Node.js crypto; the same example is in docs/API.md. */
class RequestSignatureTest {
    static final String MASTER_KEY="0123456789abcdef0123456789abcdef";
    static final String BODY="{\"addressId\":1,\"items\":[{\"productId\":2,\"quantity\":1}],\"clearCart\":false}";

    RequestSignature signature() {
        return new RequestSignature(true,MASTER_KEY,Duration.ofSeconds(300),DataSize.ofKilobytes(64),new NonceStore.Memory(10));
    }

    @Test void signKeyIsDerivedFromTokenHash() {
        assertThat(AuthService.hash("demo-token")).isEqualTo("7c43ef5ae21d43ce2743f770c68e24def1a43ee2f416d2438410c8af7af2ff2c");
        assertThat(signature().signKey(AuthService.hash("demo-token"))).isEqualTo("Ver7QLhO--GJ1ab3CvMofdHM8jQqZnE5jIbQuDsQAYA");
        assertThat(signature().signKey(AuthService.hash("other-token"))).isNotEqualTo("Ver7QLhO--GJ1ab3CvMofdHM8jQqZnE5jIbQuDsQAYA");
    }

    @Test void canonicalStringAndSignatureMatchDocumentedExample() {
        String canonical=RequestSignature.canonical("post","/api/orders","size=20&keyword=%E6%89%8B%E6%9C%BA+%E5%A3%B3&page=1&a=*~",
            1731400000000L,"3f9c2a7e-1b4d-4c8e-9a51-6d2e8f0b7c33",BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(canonical).isEqualTo("POST\n/api/orders\na=%2A~&keyword=%E6%89%8B%E6%9C%BA%20%E5%A3%B3&page=1&size=20\n1731400000000\n"
            +"3f9c2a7e-1b4d-4c8e-9a51-6d2e8f0b7c33\nc781224df34eebf5d8ca5418bb8c66eea80e37a07519542e82b4b6eded67f8e2");
        assertThat(RequestSignature.sign("Ver7QLhO--GJ1ab3CvMofdHM8jQqZnE5jIbQuDsQAYA",canonical)).isEqualTo("8DkZP0eshi5HRcfSutrAudOeKFe/KLMO9IWcuqFoxJw=");
    }

    @Test void canonicalQueryNormalizesOrderAndEncoding() {
        assertThat(RequestSignature.canonicalQuery(null)).isEmpty();
        assertThat(RequestSignature.canonicalQuery("")).isEmpty();
        assertThat(RequestSignature.canonicalQuery("b=2&a=1&a=0")).isEqualTo("a=0&a=1&b=2");
        assertThat(RequestSignature.canonicalQuery("k=a+b")).isEqualTo(RequestSignature.canonicalQuery("k=a%20b")).isEqualTo("k=a%20b");
        assertThat(RequestSignature.canonicalQuery("flag&&x=")).isEqualTo("flag=&x=");
        assertThat(RequestSignature.canonicalQuery("k=%E6%89%8B")).isEqualTo(RequestSignature.canonicalQuery("k=手"));
        assertThatThrownBy(()->RequestSignature.canonicalQuery("k=%zz")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void emptyBodyHashesEmptyString() {
        assertThat(RequestSignature.sha256Hex(new byte[0])).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test void configurationIsValidated() {
        assertThatThrownBy(()->new RequestSignature(true,"too-short",Duration.ofSeconds(300),DataSize.ofKilobytes(64),new NonceStore.Memory(10)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new RequestSignature(true,MASTER_KEY,Duration.ofMillis(10),DataSize.ofKilobytes(64),new NonceStore.Memory(10)))
            .isInstanceOf(IllegalArgumentException.class);
        // A blank key falls back to a random per-process key.
        var a=new RequestSignature(true,"",Duration.ofSeconds(300),DataSize.ofKilobytes(64),new NonceStore.Memory(10));
        var b=new RequestSignature(true,"",Duration.ofSeconds(300),DataSize.ofKilobytes(64),new NonceStore.Memory(10));
        assertThat(a.signKey("x")).isNotEqualTo(b.signKey("x"));
    }

    @Test void memoryNonceStoreRejectsReuseUntilExpiryAndIsBounded() {
        var store=new NonceStore.Memory(2);
        long now=System.currentTimeMillis();
        assertThat(store.claim("a",now+60_000)).isTrue();
        assertThat(store.claim("a",now+60_000)).isFalse();
        // An expired entry can be claimed again.
        assertThat(store.claim("b",now-1)).isTrue();
        assertThat(store.claim("b",now+60_000)).isTrue();
        assertThat(store.size()).isEqualTo(2);
        assertThatThrownBy(()->store.claim("c",now+60_000)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.status).isEqualTo(503));
        // A known key is still answered when the store is full.
        assertThat(store.claim("a",now+60_000)).isFalse();
    }
}
