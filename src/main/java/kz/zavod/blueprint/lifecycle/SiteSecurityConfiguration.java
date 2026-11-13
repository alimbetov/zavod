package kz.zavod.blueprint.lifecycle;

import org.springframework.context.annotation.*;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.userdetails.*;

@Configuration
public class SiteSecurityConfiguration {
    /** No external Blueprint API is exposed in R0.5. Deny any accidental HTTP endpoints. */
    @Bean
    @Profile("!dev-local")
    @ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth->auth.anyRequest().denyAll()).build();
    }
    /**
     * Opt-in local engineering sandbox. No network identity or PLC control is provided.
     * Unsafe requests require a non-simple header to reduce browser-origin request forgery.
     * Production/default profile remains deny-all.
     */
    @Bean
    @Profile("dev-local")
    @ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain devLocalSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf->csrf.disable());
        http.authorizeHttpRequests(auth->auth.anyRequest().access((authentication,context)->{
            var request=context.getRequest();
            String remote=request.getRemoteAddr();
            boolean loopback="127.0.0.1".equals(remote)
                    ||"::1".equals(remote)||"0:0:0:0:0:0:0:1".equals(remote);
            String method=request.getMethod();
            boolean safe="GET".equals(method)||"HEAD".equals(method)||"OPTIONS".equals(method);
            String origin=request.getHeader("Origin");
            boolean trustedOrigin=origin==null
                    ||origin.equals("http://localhost:5173")
                    ||origin.equals("http://127.0.0.1:5173")
                    ||origin.equals("http://127.0.0.1:8080")
                    ||origin.equals("http://localhost:8080");
            boolean allowed=loopback&&trustedOrigin&&(safe
                    ||"true".equals(request.getHeader("X-Zavod-Dev-Write")));
            return new AuthorizationDecision(allowed);
        }));
        return http.build();
    }
    /** Prevent Spring Boot from provisioning an unintended default internet-facing user. */
    @Bean UserDetailsService noNetworkUsers(){
        return username->{throw new UsernameNotFoundException("Network user login not configured");};
    }
}
