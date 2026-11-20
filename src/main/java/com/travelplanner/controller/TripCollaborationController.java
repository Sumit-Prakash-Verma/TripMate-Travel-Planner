
package com.travelplanner.controller;

import java.util.Locale;
import com.travelplanner.entity.AppUser;
import com.travelplanner.entity.Trip;
import com.travelplanner.entity.TripMember;
import com.travelplanner.repository.AppUserRepository;
import com.travelplanner.repository.TripMemberRepository;
import com.travelplanner.repository.TripRepository;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/trips")
public class TripCollaborationController {

        private final TripRepository tripRepository;
        private final AppUserRepository userRepository;
        private final TripMemberRepository memberRepository;

        public TripCollaborationController(
                        TripRepository tripRepository,
                        AppUserRepository userRepository,
                        TripMemberRepository memberRepository) {

                this.tripRepository = tripRepository;
                this.userRepository = userRepository;
                this.memberRepository = memberRepository;
        }

        // Invite a registered user to a trip.
        @PostMapping("/{tripId}/invite")
        public ResponseEntity<?> inviteMember(
                        @PathVariable Long tripId,
                        @RequestBody Map<String, String> request,
                        Authentication authentication) {

                String email = request.get("email");

                if (email == null || email.isBlank()) {
                        return ResponseEntity.badRequest()
                                        .body(Map.of("message", "Email is required"));
                }

                Optional<AppUser> ownerOptional = userRepository.findByEmail(authentication.getName());

                if (ownerOptional.isEmpty()) {
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                        .body(Map.of("message", "User not found"));
                }

                AppUser owner = ownerOptional.get();

                Optional<Trip> tripOptional = tripRepository.findByIdAndOwner_Id(tripId, owner.getId());

                if (tripOptional.isEmpty()) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                        .body(Map.of(
                                                        "message",
                                                        "Only the trip owner can invite members"));
                }

                String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

                Optional<AppUser> invitedUserOptional = userRepository.findByEmail(normalizedEmail);

                if (invitedUserOptional.isEmpty()) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                        .body(Map.of(
                                                        "message",
                                                        "This email is not registered. Ask the user to register first."));
                }

                AppUser invitedUser = invitedUserOptional.get();

                if (owner.getId().equals(invitedUser.getId())) {
                        return ResponseEntity.badRequest()
                                        .body(Map.of("message", "You cannot invite yourself"));
                }

                if (memberRepository.existsByTrip_IdAndUser_Id(
                                tripId, invitedUser.getId())) {
                        return ResponseEntity.badRequest()
                                        .body(Map.of(
                                                        "message",
                                                        "This user is already a trip member"));
                }

                TripMember member = new TripMember();
                member.setTrip(tripOptional.get());
                member.setUser(invitedUser);
                member.setRole("MEMBER");

                memberRepository.save(member);

                return ResponseEntity.ok(Map.of(
                                "message", "Member added to trip successfully",
                                "tripId", tripId,
                                "memberEmail", invitedUser.getEmail(),
                                "role", "MEMBER"));
        }

        // Remove a member. Only the trip owner can do this.
        @DeleteMapping("/{tripId}/members")
        public ResponseEntity<?> removeMember(
                        @PathVariable Long tripId,
                        @RequestParam String email,
                        Authentication authentication) {

                if (email == null || email.isBlank()) {
                        return ResponseEntity.badRequest()
                                        .body(Map.of("message", "Member email is required"));
                }

                Optional<AppUser> ownerOptional = userRepository.findByEmail(authentication.getName());

                if (ownerOptional.isEmpty()) {
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                        .body(Map.of("message", "User not found"));
                }

                AppUser owner = ownerOptional.get();

                // Confirm that the current user owns this trip.
                Optional<Trip> tripOptional = tripRepository.findByIdAndOwner_Id(tripId, owner.getId());

                if (tripOptional.isEmpty()) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                        .body(Map.of(
                                                        "message",
                                                        "Only the trip owner can remove members"));
                }

                String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

                Optional<AppUser> targetOptional = userRepository.findByEmail(normalizedEmail);

                if (targetOptional.isEmpty()) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                        .body(Map.of("message", "User not found"));
                }

                AppUser targetUser = targetOptional.get();

                // The owner cannot remove themselves through this endpoint.
                if (owner.getId().equals(targetUser.getId())) {
                        return ResponseEntity.badRequest()
                                        .body(Map.of(
                                                        "message",
                                                        "The trip owner cannot remove themselves"));
                }

                Optional<TripMember> memberOptional = memberRepository.findByTrip_IdAndUser_Id(
                                tripId, targetUser.getId());

                if (memberOptional.isEmpty()) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                        .body(Map.of(
                                                        "message",
                                                        "This user is not a member of this trip"));
                }

                memberRepository.delete(memberOptional.get());

                return ResponseEntity.ok(Map.of(
                                "message", "Member removed successfully",
                                "tripId", tripId,
                                "memberEmail", targetUser.getEmail()));
        }

        // Get the owner and members of a trip.
        @GetMapping("/{tripId}/members")
        @Transactional(readOnly = true)
        public ResponseEntity<?> getTripMembers(
                        @PathVariable Long tripId,
                        Authentication authentication) {

                Optional<AppUser> currentUserOptional = userRepository.findByEmail(authentication.getName());

                if (currentUserOptional.isEmpty()) {
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                        .body(Map.of("message", "User not found"));
                }

                AppUser currentUser = currentUserOptional.get();

                Optional<Trip> tripOptional = tripRepository.findByIdAndOwner_Id(
                                tripId, currentUser.getId());

                if (tripOptional.isEmpty()) {
                        tripOptional = memberRepository
                                        .findByTrip_IdAndUser_Id(tripId, currentUser.getId())
                                        .map(TripMember::getTrip);
                }

                if (tripOptional.isEmpty()) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                        .body(Map.of(
                                                        "message",
                                                        "Trip not found or access denied"));
                }

                Trip trip = tripOptional.get();

                List<Map<String, Object>> members = new ArrayList<>();

                // Add the trip owner.
                AppUser owner = trip.getOwner();

                members.add(Map.of(
                                "fullName", owner.getFullName(),
                                "email", owner.getEmail(),
                                "role", "OWNER"));

                // Add invited members.
                List<TripMember> tripMembers = memberRepository.findByTrip_Id(tripId);

                for (TripMember member : tripMembers) {
                        AppUser user = member.getUser();

                        members.add(Map.of(
                                        "fullName", user.getFullName(),
                                        "email", user.getEmail(),
                                        "role", member.getRole()));
                }

                return ResponseEntity.ok(members);
        }
}
