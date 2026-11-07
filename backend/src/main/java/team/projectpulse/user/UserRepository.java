package team.projectpulse.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<PeerEvaluationUser, Integer> {

    boolean existsByIdAndEnabledTrue(Integer userId);

    Optional<PeerEvaluationUser> findByUsername(String username);

    Optional<PeerEvaluationUser> findByEmail(String email);

    @Modifying
    @Query("update PeerEvaluationUser u set u.password = :password where u.email = :email")
    int updatePasswordByEmail(String email, String password);

    List<PeerEvaluationUser> findByEmailIn(Collection<String> emails);

}
