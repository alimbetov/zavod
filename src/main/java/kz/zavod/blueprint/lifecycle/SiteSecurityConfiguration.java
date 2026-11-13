package kz.zavod.blueprint.lifecycle;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.userdetails.*;

@Configuration
public class SiteSecurityConfiguration {
    /** No external Blueprint API is exposed in R0.5. Deny any accidental HTTP endpoints. */
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth->auth.anyRequest().denyAll()).build();
    }
    /** Prevent Spring Boot from provisioning an unintended default internet-facing user. */
    @Bean UserDetailsService noNetworkUsers(){
        return username->{throw new UsernameNotFoundException("Network user login not configured");};
    }
}
