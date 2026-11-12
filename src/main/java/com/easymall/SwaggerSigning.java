package com.easymall;

import jakarta.servlet.http.HttpServletRequest;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springdoc.webmvc.ui.SwaggerIndexPageTransformer;
import org.springdoc.webmvc.ui.SwaggerIndexTransformer;
import org.springdoc.webmvc.ui.SwaggerWelcomeCommon;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.ResourceTransformerChain;
import org.springframework.web.servlet.resource.TransformedResource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Makes "Try it out" work with request signing: prepends swagger/request-signing.js to the
 * swagger-initializer.js that springdoc generates. The script wraps SwaggerUIBundle so that
 * its requestInterceptor signs Bearer requests and its responseInterceptor keeps the signKey
 * from the login response. Replaces springdoc's default transformer (@ConditionalOnMissingBean).
 */
@Configuration
public class SwaggerSigning {
    @Bean SwaggerIndexTransformer signingSwaggerIndexTransformer(SwaggerUiConfigProperties config,SwaggerUiOAuthProperties oauth,
                                                                 SwaggerWelcomeCommon welcome,ObjectMapperProvider mapper) {
        String script;
        try { script=new ClassPathResource("swagger/request-signing.js").getContentAsString(StandardCharsets.UTF_8); }
        catch(IOException e) { throw new UncheckedIOException(e); }
        return new SwaggerIndexPageTransformer(config,oauth,welcome,mapper) {
            @Override public Resource transform(HttpServletRequest request,Resource resource,ResourceTransformerChain chain) throws IOException {
                Resource transformed=super.transform(request,resource,chain);
                if(!resource.getURL().toString().endsWith("/swagger-initializer.js")) return transformed;
                String initializer=transformed.getContentAsString(StandardCharsets.UTF_8);
                return new TransformedResource(resource,(script+"\n"+initializer).getBytes(StandardCharsets.UTF_8));
            }
        };
    }
}
