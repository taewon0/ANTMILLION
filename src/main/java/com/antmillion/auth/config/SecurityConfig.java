package com.antmillion.auth.config;

import javax.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import com.antmillion.auth.jwt.AutoRefreshFilter;
import com.antmillion.auth.jwt.CookieUtil;
import com.antmillion.auth.jwt.JwtAuthFilter;
import com.antmillion.auth.jwt.JwtProvider;
import com.antmillion.auth.service.SignService;
import com.antmillion.auth.token.RefreshTokenStore;

import io.jsonwebtoken.Claims;

@Configuration
@EnableMethodSecurity
@EnableWebSecurity
public class SecurityConfig {

	private final RefreshTokenStore refreshTokenStore;
	private final JwtProvider jwtProvider;
	private final SignService signService;

	public SecurityConfig(RefreshTokenStore refreshTokenStore, JwtProvider jwtProvider, SignService signService) {
		this.refreshTokenStore = refreshTokenStore;
		this.jwtProvider = jwtProvider;
		this.signService = signService;
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {

		http
			.csrf(csrf -> csrf.disable())
			.formLogin(form -> form.disable())
			.httpBasic(basic -> basic.disable())
			.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
					// 로그인 필요
					.requestMatchers(new AntPathRequestMatcher("/mypage/**")).authenticated()
					.requestMatchers(new AntPathRequestMatcher("/mission/**")).authenticated()
					.requestMatchers(new AntPathRequestMatcher("/myinfo/**")).authenticated()
					.anyRequest().permitAll())
			.exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, e) -> {
	            final String ctx = req.getContextPath();
	            final String uri = req.getRequestURI();

	            final boolean isApi =
	                    uri.startsWith(ctx + "/api/") ||
	                    uri.startsWith(ctx + "/auth/");

	            if (isApi) {
	                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
	            } else {
	                res.sendRedirect(ctx + "/login");
	            }
	        }))
			.logout(logout -> logout
		            .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "POST"))
		            .addLogoutHandler((req, res, auth) -> {
		                String rt = CookieUtil.getCookieValue(req, "RT");

		                if (rt != null && jwtProvider.isValid(rt)) {
		                    Claims claims = jwtProvider.parseClaims(rt);
		                    long userId = Long.parseLong(claims.getSubject());
		                    refreshTokenStore.delete(userId);
		                }

		                CookieUtil.deleteCookie(res, "RT");
		                CookieUtil.deleteCookie(res, "AT");
		            })
		            .logoutSuccessUrl("/")
		        );

		// AT 만료시: RT로 자동 재발급
		http.addFilterBefore(autoRefreshFilter(), UsernamePasswordAuthenticationFilter.class);

		// AT 인증
		http.addFilterAfter(jwtAuthFilter, AutoRefreshFilter.class);

		return http.build();
	}

	@Bean
	public JwtAuthFilter jwtAuthFilter() {
		return new JwtAuthFilter(jwtProvider);
	}

	@Bean
	public AutoRefreshFilter autoRefreshFilter() {
		return new AutoRefreshFilter(jwtProvider, refreshTokenStore, signService);
	}
}
