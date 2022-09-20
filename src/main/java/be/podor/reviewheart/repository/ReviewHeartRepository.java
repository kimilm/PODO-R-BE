package be.podor.reviewheart.repository;

import be.podor.review.model.Review;
import be.podor.reviewheart.model.ReviewHeart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Set;

public interface ReviewHeartRepository extends JpaRepository<ReviewHeart, Long> {

    boolean existsByReviewAndCreatedBy(Review review, Long memberId);

    @Query(value = "SELECT rh.review.reviewId " +
            "FROM ReviewHeart rh " +
            "WHERE rh.createdBy = :createdBy " +
            "AND rh.review IN :reviews")
    Set<Long> findHeartedReviews(Long createdBy, List<Review> reviews);

    void deleteByReviewAndCreatedBy(Review review, Long memberId);

}
