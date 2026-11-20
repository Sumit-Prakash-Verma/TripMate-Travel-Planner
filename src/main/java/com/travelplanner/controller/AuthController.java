
package com.travelplanner.controller;

import com.travelplanner.entity.AppUser;
import com.travelplanner.repository.AppUserRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

        private final AppUserRepository userRepository;
        private final PasswordEncoder passwordEncoder;
        private final AuthenticationManager authenticationManager;
        private final SecurityContextRepository securityContextRepository;

        public AuthController(
                        AppUserRepository userRepository,
                        PasswordEncoder passwordEncoder,
                        AuthenticationManager authenticationManager,
                        SecurityContextRepository securityContextRepository) {
                this.userRepository = userRepository;
                this.passwordEncoder = passwordEncoder;
                this.authenticationManager = authenticationManager;
                this.securityContextRepository = securityContextRepository;
        }

        public record RegisterRequest(
                        @NotBlank @Size(max = 100) String fullName,
                        @NotBlank @Email @Size(max = 150) String email,
                        @NotBlank @Size(min = 8, max = 72) String password) {
        }

        public record LoginRequest(
                        @NotBlank @Email String email,
                        @NotBlank String password) {
        }

        public record UserResponse(Long id, String fullName, String email) {
        }

        @PostMapping("/register")
        public ResponseEntity<?> register(
                        @Valid @RequestBody RegisterRequest request) {

                String email = request.email().trim().toLowerCase(Locale.ROOT);

                if (userRepository.existsByEmail(email)) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                        .body(Map.of("message",
                                                        "An account with this email already exists."));
                }

                AppUser user = new AppUser();
                user.setFullName(request.fullName().trim());
                user.setEmail(email);
                user.setPassword(passwordEncoder.encode(request.password()));

                AppUser savedUser = userRepository.save(user);

                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(new UserResponse(
                                                savedUser.getId(),
                                                savedUser.getFullName(),
                                                savedUser.getEmail()));
        }

        @PostMapping("/login")
        public ResponseEntity<?> login(
                        @Valid @RequestBody LoginRequest request,
                        HttpServletRequest httpRequest,
                        HttpServletResponse httpResponse) {

                String email = request.email().trim().toLowerCase(Locale.ROOT);

                try {
                        Authentication authentication = authenticationManager.authenticate(
                                        UsernamePasswordAuthenticationToken.unauthenticated(
                                                        email, request.password()));

                        SecurityContext context = SecurityContextHolder.createEmptyContext();
                        context.setAuthentication(authentication);
                        SecurityContextHolder.setContext(context);

                        // Create a session and rotate its ID after authentication.
                        httpRequest.getSession(true);
                        httpRequest.changeSessionId();

                        // Persist authenticated context in the HTTP session.
                        securityContextRepository.saveContext(
                                        context, httpRequest, httpResponse);

                        AppUser user = userRepository.findByEmail(email).orElseThrow();

                        return ResponseEntity.ok(new UserResponse(
                                        user.getId(), user.getFullName(), user.getEmail()));

                } catch (AuthenticationException exception) {
                        SecurityContextHolder.clearContext();
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                        .body(Map.of("message", "Invalid email or password."));
                }
        }

        @GetMapping("/me")
        public ResponseEntity<?> currentUser(Authentication authentication) {
                if (authentication == null
                                || !authentication.isAuthenticated()
                                || authentication.getPrincipal().equals("anonymousUser")) {
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                        .body(Map.of("message", "Please log in."));
                }

                return userRepository.findByEmail(authentication.getName())
                                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(new UserResponse(
                                                user.getId(),
                                                user.getFullName(),
                                                user.getEmail())))
                                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                                .body(Map.of("message", "Please log in.")));
        }

        @PostMapping("/logout")
        public ResponseEntity<?> logout(HttpServletRequest request) {
                SecurityContextHolder.clearContext();

                HttpSession session = request.getSession(false);
                if (session != null) {
                        session.invalidate();
                }

                return ResponseEntity.ok(Map.of("message", "Logged out successfully."));
        }
}
