package com.easymall;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SIGNATURE_ENABLED=false keeps plain Bearer authentication working. */
@SpringBootTest(properties="app.signature.enabled=false") @AutoConfigureMockMvc @ActiveProfiles("demo")
class SignatureDisabledTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;

    @Test void unsignedBearerRequestsAreAcceptedWhenDisabled() throws Exception {
        var c=new Requests.Credentials("d"+UUID.randomUUID().toString().replace("-",""),"PlainPass123!");
        auth.register(c);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+auth.login(c).get("token"))).andExpect(status().isOk());
    }
}
