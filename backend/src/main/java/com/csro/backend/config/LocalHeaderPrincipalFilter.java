package com.csro.backend.config;


import java.io.IOException;
import java.security.Principal;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Profile("local")
public class LocalHeaderPrincipalFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Demo-Subject";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String subject = request.getHeader(HEADER_NAME);

        if (StringUtils.hasText(subject)) {
            String trimmedSubject = subject.trim();
            HttpServletRequest wrappedRequest = new HttpServletRequestWrapper(request) {
                @Override
                public Principal getUserPrincipal() {
                    return () -> trimmedSubject;
                }
            };
            filterChain.doFilter(wrappedRequest, response);
            return;
        }

        filterChain.doFilter(request, response);
    }
}