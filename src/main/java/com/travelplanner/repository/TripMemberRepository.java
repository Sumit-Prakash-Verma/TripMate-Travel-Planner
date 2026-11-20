
package com.travelplanner.repository;

import com.travelplanner.entity.TripMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.travelplanner.entity.Trip;

public interface TripMemberRepository extends JpaRepository<TripMember, Long> {

    List<TripMember> findByUser_Id(Long userId);

    List<TripMember> findByTrip_Id(Long tripId);

    Optional<TripMember> findByTrip_IdAndUser_Id(Long tripId, Long userId);

    boolean existsByTrip_IdAndUser_Id(Long tripId, Long userId);

    @Query("SELECT tm.trip FROM TripMember tm WHERE tm.user.id = :userId")
    List<Trip> findSharedTripsByUserId(@Param("userId") Long userId);

}
