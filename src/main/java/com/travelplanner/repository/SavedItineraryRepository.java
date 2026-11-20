
package com.travelplanner.repository;

import com.travelplanner.entity.AppUser;
import com.travelplanner.entity.SavedItinerary;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedItineraryRepository
        extends JpaRepository<SavedItinerary, Long> {

    List<SavedItinerary> findByOwnerOrderByCreatedAtDesc(
            AppUser owner);

    Optional<SavedItinerary> findByIdAndOwner(
            Long id, AppUser owner);
}
