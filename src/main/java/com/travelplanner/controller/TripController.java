
package com.travelplanner.controller;

import com.travelplanner.entity.AppUser;
import com.travelplanner.entity.Trip;
import com.travelplanner.entity.TripMember;
import com.travelplanner.repository.AppUserRepository;
import com.travelplanner.repository.TripMemberRepository;
import com.travelplanner.repository.TripRepository;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/trips")
public class TripController {

    private final TripRepository tripRepository;
    private final AppUserRepository userRepository;
    private final TripMemberRepository memberRepository;

    public TripController(
            TripRepository tripRepository,
            AppUserRepository userRepository,
            TripMemberRepository memberRepository) {

        this.tripRepository = tripRepository;
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
    }

    private AppUser getCurrentUser(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("Logged-in user not found"));
    }

    // Get all trips owned by the user and shared with the user
    @GetMapping
    public List<Trip> getAllTrips(Authentication authentication) {

        AppUser user = getCurrentUser(authentication);

        Map<Long, Trip> accessibleTrips = new LinkedHashMap<>();

        // Trips owned by the logged-in user
        for (Trip trip : tripRepository.findByOwner_Id(user.getId())) {
            accessibleTrips.put(trip.getId(), trip);
        }

        // Trips shared with the logged-in user
        for (Trip trip : memberRepository.findSharedTripsByUserId(user.getId())) {
            accessibleTrips.put(trip.getId(), trip);
        }

        return List.copyOf(accessibleTrips.values());
    }

    // Return only the IDs of trips owned by the logged-in user
    @GetMapping("/owned-ids")
    public List<Long> getOwnedTripIds(Authentication authentication) {

        AppUser user = getCurrentUser(authentication);

        return tripRepository.findByOwner_Id(user.getId())
                .stream()
                .map(Trip::getId)
                .toList();
    }

    // Get a single trip if the user owns it or is a member
    @GetMapping("/{id}")
    public ResponseEntity<Trip> getTripById(
            @PathVariable Long id,
            Authentication authentication) {

        AppUser user = getCurrentUser(authentication);

        var ownedTrip = tripRepository.findByIdAndOwner_Id(id, user.getId());

        if (ownedTrip.isPresent()) {
            return ResponseEntity.ok(ownedTrip.get());
        }

        return memberRepository
                .findByTrip_IdAndUser_Id(id, user.getId())
                .map(TripMember::getTrip)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Create a trip; owner comes from the authenticated session
    @PostMapping
    public ResponseEntity<Trip> createTrip(
            @RequestBody Trip trip,
            Authentication authentication) {

        AppUser user = getCurrentUser(authentication);

        trip.setId(null);
        trip.setOwner(user);

        Trip savedTrip = tripRepository.save(trip);

        return ResponseEntity.status(HttpStatus.CREATED).body(savedTrip);
    }

    // Only the owner can update a trip
    @PutMapping("/{id}")
    public ResponseEntity<Trip> updateTrip(
            @PathVariable Long id,
            @RequestBody Trip updatedTrip,
            Authentication authentication) {

        AppUser user = getCurrentUser(authentication);

        return tripRepository.findByIdAndOwner_Id(id, user.getId())
                .map(existingTrip -> {

                    existingTrip.setTitle(updatedTrip.getTitle());
                    existingTrip.setDestination(updatedTrip.getDestination());
                    existingTrip.setStartDate(updatedTrip.getStartDate());
                    existingTrip.setEndDate(updatedTrip.getEndDate());
                    existingTrip.setBudget(updatedTrip.getBudget());
                    existingTrip.setDescription(updatedTrip.getDescription());

                    Trip savedTrip = tripRepository.save(existingTrip);

                    return ResponseEntity.ok(savedTrip);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Only the owner can delete a trip
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> deleteTrip(
            @PathVariable Long id,
            Authentication authentication) {

        AppUser user = getCurrentUser(authentication);

        var tripOptional = tripRepository.findByIdAndOwner_Id(id, user.getId());

        if (tripOptional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Trip trip = tripOptional.get();

        // Delete trip members first to avoid foreign-key constraint error
        List<TripMember> members = memberRepository.findByTrip_Id(id);

        memberRepository.deleteAll(members);

        // Now delete the trip
        tripRepository.delete(trip);

        return ResponseEntity.noContent().build();
    }
}
