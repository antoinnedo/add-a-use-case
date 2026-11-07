package team.projectpulse.student;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StudentRepository extends JpaRepository<Student, Integer>, JpaSpecificationExecutor<Student> {

    // Keep the scope in the query; the lock should target the student, not the assignment rows.
    String ACCESSIBLE_STUDENT = """
            select s from Student s where s.id = :studentId and (
                (:allowSelf = true and s.id = :callerId)
                or (:instructor = true and s.section.sectionId in (
                    select section.sectionId from Section section
                    join section.instructors assigned where assigned.id = :callerId))
                or (:admin = true and s.section.sectionId in (
                    select section.sectionId from Section section
                    join section.course course where course.courseAdmin.id = :callerId))
            )
            """;

    @Query(ACCESSIBLE_STUDENT)
    Optional<Student> findAccessibleStudent(Integer studentId, Integer callerId,
                                            boolean allowSelf, boolean instructor, boolean admin);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(ACCESSIBLE_STUDENT)
    Optional<Student> findAccessibleStudentForUpdate(Integer studentId, Integer callerId,
                                                     boolean allowSelf, boolean instructor, boolean admin);

    boolean existsByIdAndEnabledTrue(Integer studentId);

    // Find all students in a section
    List<Student> findBySectionSectionId(Integer sectionId);

    List<Student> findByTeamTeamId(Integer teamId);
}
