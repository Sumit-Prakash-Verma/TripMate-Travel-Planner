package com.travelplanner;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.travelplanner.entity.AppUser;
import com.travelplanner.entity.SavedItinerary;
import com.travelplanner.repository.AppUserRepository;
import com.travelplanner.repository.SavedItineraryRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/saved-itineraries")
public class SavedItineraryController {

        private final SavedItineraryRepository itineraryRepository;
        private final AppUserRepository userRepository;
        private final ObjectMapper objectMapper;

        public SavedItineraryController(
                        SavedItineraryRepository itineraryRepository,
                        AppUserRepository userRepository,
                        ObjectMapper objectMapper) {

                this.itineraryRepository = itineraryRepository;
                this.userRepository = userRepository;
                this.objectMapper = objectMapper;
        }

        // SAVE A NEW ITINERARY
        @PostMapping
        public ResponseEntity<?> saveItinerary(
                        @RequestBody Map<String, Object> request,
                        Authentication authentication) {

                if (!isLoggedIn(authentication)) {
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                        .body(Map.of(
                                                        "error", "Please log in to save an itinerary."));
                }

                try {
                        String destination = requiredText(request, "destination");

                        LocalDate startDate = LocalDate.parse(
                                        requiredText(request, "startDate"));

                        LocalDate endDate = LocalDate.parse(
                                        requiredText(request, "endDate"));

                        Object budgetValue = request.get("budget");

                        if (budgetValue == null) {
                                throw new IllegalArgumentException("Budget is required.");
                        }

                        BigDecimal budget = new BigDecimal(
                                        budgetValue.toString());

                        Object itinerary = request.get("itinerary");

                        if (endDate.isBefore(startDate)) {
                                return ResponseEntity.badRequest()
                                                .body(Map.of(
                                                                "error",
                                                                "End date cannot be before start date."));
                        }

                        if (budget.compareTo(BigDecimal.ZERO) <= 0) {
                                return ResponseEntity.badRequest()
                                                .body(Map.of(
                                                                "error",
                                                                "Budget must be greater than zero."));
                        }

                        if (itinerary == null) {
                                return ResponseEntity.badRequest()
                                                .body(Map.of(
                                                                "error",
                                                                "Itinerary data is required."));
                        }

                        AppUser owner = findCurrentUser(authentication);

                        if (owner == null) {
                                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                                .body(Map.of(
                                                                "error",
                                                                "Logged-in user was not found."));
                        }

                        SavedItinerary saved = new SavedItinerary();

                        saved.setDestination(destination);
                        saved.setStartDate(startDate);
                        saved.setEndDate(endDate);
                        saved.setBudget(budget);
                        saved.setItineraryJson(
                                        objectMapper.writeValueAsString(itinerary));
                        saved.setOwner(owner);

                        SavedItinerary result = itineraryRepository.save(saved);

                        Map<String, Object> response = new LinkedHashMap<>();

                        response.put("id", result.getId());
                        response.put("destination", result.getDestination());
                        response.put("startDate", result.getStartDate());
                        response.put("endDate", result.getEndDate());
                        response.put("budget", result.getBudget());
                        response.put("createdAt", result.getCreatedAt());
                        response.put("message", "Itinerary saved successfully.");

                        return ResponseEntity.status(HttpStatus.CREATED)
                                        .body(response);

                } catch (IllegalArgumentException | JacksonException e) {
                        return ResponseEntity.badRequest()
                                        .body(Map.of(
                                                        "error",
                                                        "Please provide valid itinerary details."));
                } catch (Exception e) {
                        return ResponseEntity.status(
                                        HttpStatus.INTERNAL_SERVER_ERROR)
                                        .body(Map.of(
                                                        "error", "Could not save itinerary."));
                }
        }

        // GET ALL ITINERARIES FOR THE LOGGED-IN USER
        @GetMapping
        public ResponseEntity<?> getMyItineraries(
                        Authentication authentication) {

                if (!isLoggedIn(authentication)) {
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                        .body(Map.of("error", "Please log in first."));
                }

                AppUser owner = findCurrentUser(authentication);

                if (owner == null) {
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                        .body(Map.of(
                                                        "error", "Logged-in user was not found."));
                }

                List<SavedItinerary> savedItems = itineraryRepository
                                .findByOwnerOrderByCreatedAtDesc(owner);

                List<Map<String, Object>> response = new ArrayList<>();

                for (SavedItinerary item : savedItems) {
                        response.add(toResponseMap(item));
                }

                return ResponseEntity.ok(response);
        }

        // GET ONE ITINERARY BELONGING TO THE LOGGED-IN USER
        @GetMapping("/{id}")
        public ResponseEntity<?> getMyItinerary(
                        @PathVariable Long id,
                        Authentication authentication) {

                if (!isLoggedIn(authentication)) {
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                        .body(Map.of("error", "Please log in first."));
                }

                AppUser owner = findCurrentUser(authentication);

                if (owner == null) {
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                        .body(Map.of(
                                                        "error", "Logged-in user was not found."));
                }

                SavedItinerary item = itineraryRepository
                                .findByIdAndOwner(id, owner)
                                .orElse(null);

                if (item == null) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                        .body(Map.of(
                                                        "error",
                                                        "Itinerary not found for this account."));
                }

                return ResponseEntity.ok(toResponseMap(item));
        }

        // DELETE ONE ITINERARY BELONGING TO THE LOGGED-IN USER
        @DeleteMapping("/{id}")
        public ResponseEntity<?> deleteMyItinerary(
                        @PathVariable Long id,
                        Authentication authentication) {

                if (!isLoggedIn(authentication)) {
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                        .body(Map.of(
                                                        "error", "Please log in first."));
                }

                AppUser owner = findCurrentUser(authentication);

                if (owner == null) {
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                        .body(Map.of(
                                                        "error", "Logged-in user was not found."));
                }

                // Ensure that the itinerary belongs to the logged-in user.
                SavedItinerary item = itineraryRepository
                                .findByIdAndOwner(id, owner)
                                .orElse(null);

                if (item == null) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                        .body(Map.of(
                                                        "error",
                                                        "Itinerary not found for this account."));
                }

                itineraryRepository.delete(item);

                return ResponseEntity.ok(
                                Map.of(
                                                "message", "Itinerary deleted successfully.",
                                                "id", id));
        }

        // UPDATE ONE ITINERARY BELONGING TO THE LOGGED-IN USER
        @PutMapping("/{id}")
        public ResponseEntity<?> updateMyItinerary(
                        @PathVariable Long id,
                        @RequestBody Map<String, Object> request,
                        Authentication authentication) {

                if (!isLoggedIn(authentication)) {
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                        .body(Map.of("error", "Please log in first."));
                }

                AppUser owner = findCurrentUser(authentication);

                if (owner == null) {
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                        .body(Map.of("error", "Logged-in user was not found."));
                }

                SavedItinerary item = itineraryRepository
                                .findByIdAndOwner(id, owner)
                                .orElse(null);

                if (item == null) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                        .body(Map.of(
                                                        "error", "Itinerary not found for this account."));
                }

                try {
                        String destination = requiredText(request, "destination");

                        LocalDate startDate = LocalDate.parse(
                                        requiredText(request, "startDate"));

                        LocalDate endDate = LocalDate.parse(
                                        requiredText(request, "endDate"));

                        Object budgetValue = request.get("budget");

                        if (budgetValue == null) {
                                throw new IllegalArgumentException("Budget is required.");
                        }

                        BigDecimal budget = new BigDecimal(budgetValue.toString());

                        Object itinerary = request.get("itinerary");

                        if (endDate.isBefore(startDate)) {
                                return ResponseEntity.badRequest()
                                                .body(Map.of(
                                                                "error", "End date cannot be before start date."));
                        }

                        if (budget.compareTo(BigDecimal.ZERO) <= 0) {
                                return ResponseEntity.badRequest()
                                                .body(Map.of(
                                                                "error", "Budget must be greater than zero."));
                        }

                        if (itinerary == null) {
                                return ResponseEntity.badRequest()
                                                .body(Map.of(
                                                                "error", "Itinerary data is required."));
                        }

                        item.setDestination(destination);
                        item.setStartDate(startDate);
                        item.setEndDate(endDate);
                        item.setBudget(budget);
                        item.setItineraryJson(
                                        objectMapper.writeValueAsString(itinerary));

                        SavedItinerary updated = itineraryRepository.save(item);

                        return ResponseEntity.ok(Map.of(
                                        "id", updated.getId(),
                                        "destination", updated.getDestination(),
                                        "startDate", updated.getStartDate(),
                                        "endDate", updated.getEndDate(),
                                        "budget", updated.getBudget(),
                                        "message", "Itinerary updated successfully."));

                } catch (IllegalArgumentException | JacksonException e) {
                        return ResponseEntity.badRequest()
                                        .body(Map.of(
                                                        "error", "Please provide valid itinerary details."));
                } catch (Exception e) {
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                        .body(Map.of("error", "Could not update itinerary."));
                }
        }

        // CHECK LOGIN STATUS
        private boolean isLoggedIn(Authentication authentication) {
                return authentication != null
                                && authentication.isAuthenticated()
                                && !"anonymousUser".equals(authentication.getName());
        }

        // FIND CURRENT USER
        private AppUser findCurrentUser(
                        Authentication authentication) {

                return userRepository
                                .findByEmail(authentication.getName())
                                .orElse(null);
        }

        // FORMAT API RESPONSE
        private Map<String, Object> toResponseMap(
                        SavedItinerary item) {

                Map<String, Object> response = new LinkedHashMap<>();

                response.put("id", item.getId());
                response.put("destination", item.getDestination());
                response.put("startDate", item.getStartDate());
                response.put("endDate", item.getEndDate());
                response.put("budget", item.getBudget());
                response.put("itineraryJson", item.getItineraryJson());
                response.put("createdAt", item.getCreatedAt());

                return response;
        }

        // VALIDATE REQUIRED TEXT
        private String requiredText(
                        Map<String, Object> request,
                        String key) {

                Object value = request.get(key);

                if (value == null || value.toString().isBlank()) {
                        throw new IllegalArgumentException(
                                        key + " is required.");
                }

                return value.toString().trim();
        }
}
