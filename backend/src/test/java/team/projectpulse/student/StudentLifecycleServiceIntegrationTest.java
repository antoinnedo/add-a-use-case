package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.evaluation.EvaluationService;
import team.projectpulse.evaluation.PeerEvaluationAverage;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.student.converter.StudentToStudentDtoConverter;
import team.projectpulse.system.exception.ObjectNotFoundException;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudentLifecycleServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired StudentService studentService;
    @Autowired StudentRepository studentRepository;
    @Autowired StudentToStudentDtoConverter studentConverter;
    @Autowired EvaluationService evaluationService;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void unassignedCourseOwnerDeactivatesOnlyTheAccountAndPreservesItsHistory() throws Exception {
        Student john = student("j.smith@abc.edu");
        Instructor owner = john.getSection().getCourse().getCourseAdmin();
        assertThat(john.getSection().getInstructors().remove(owner)).isTrue();
        assertThat(john.getTeam()).isNotNull();
        Integer studentId = john.getId();
        Integer sectionId = john.getSection().getSectionId();
        var before = this.studentConverter.convert(john);
        var activityIds = activityIds(studentId);
        assertThat(activityIds).isNotEmpty();
        var historical = reportRow(sectionId, "2023-W32", studentId);
        assertThat(historical.getAverageTotalScore()).isPositive();
        assertThat(historical.getPublicComments()).isNotEmpty();
        assertThat(historical.getPrivateComments()).isNotEmpty();
        this.entityManager.flush();
        this.entityManager.clear();

        assertThat(asUser(owner.getId(), "ROLE_admin",
                () -> this.studentService.deactivateStudent(studentId)).changed()).isTrue();
        this.entityManager.flush();
        this.entityManager.clear();

        var persisted = this.studentConverter.convert(this.studentRepository.findById(studentId).orElseThrow());
        assertThat(persisted.enabled()).isFalse();
        assertThat(persisted).usingRecursiveComparison().ignoringFields("enabled").isEqualTo(before);
        assertThat(activityIds(studentId)).containsExactlyInAnyOrderElementsOf(activityIds);
        assertThat(reportRow(sectionId, "2023-W32", studentId)).usingRecursiveComparison()
                .ignoringCollectionOrder().isEqualTo(historical);
        var emptyWeek = reportRow(sectionId, "2023-W40", studentId);
        assertThat(emptyWeek.getAverageTotalScore()).isZero();
        assertThat(emptyWeek.getPublicComments()).isEmpty();
        assertThat(emptyWeek.getRatingAverages()).isEmpty();

        assertThat(asUser(owner.getId(), "ROLE_admin",
                () -> this.studentService.deactivateStudent(studentId)).changed()).isFalse();
    }

    @Test
    void assignedInstructorDeactivatesAnUnteamedStudent() throws Exception {
        Student tracy = student("t.nicholson@abc.edu");
        Integer instructorId = instructor("b.gates@abc.edu").getId();
        Integer sectionId = tracy.getSection().getSectionId();
        Integer studentId = tracy.getId();
        assertThat(tracy.getTeam()).isNull();

        assertThat(asUser(instructorId, "ROLE_instructor",
                () -> this.studentService.deactivateStudent(studentId)).changed()).isTrue();
        this.entityManager.flush();
        this.entityManager.clear();

        Student persisted = this.studentRepository.findById(studentId).orElseThrow();
        assertThat(persisted.isEnabled()).isFalse();
        assertThat(persisted.getTeam()).isNull();
        assertThat(persisted.getSection().getSectionId()).isEqualTo(sectionId);
    }

    @Test
    void foreignCourseAdminCannotLoadAStudentThroughTheLifecycleService() {
        Integer studentId = student("j.smith@abc.edu").getId();
        Integer foreignAdminId = instructor("t.cook@abc.edu").getId();

        assertThatThrownBy(() -> asUser(foreignAdminId, "ROLE_admin",
                () -> this.studentService.deactivateStudent(studentId)))
                .isInstanceOf(ObjectNotFoundException.class);
        assertThat(this.studentRepository.findById(studentId).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousDeactivationsReportExactlyOneTransition() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(this.transactionManager);
        Integer ownerId = transaction.execute(status -> instructor("b.wei@abc.edu").getId());
        Integer studentId = transaction.execute(status -> {
            Student temporary = new Student("lifecycle-race@abc.edu", "Lifecycle", "Race",
                    "lifecycle-race@abc.edu", "unused", true, "student");
            temporary.setSection(student("j.smith@abc.edu").getSection());
            return this.studentRepository.save(temporary).getId();
        });

        CountDownLatch firstChanged = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> asUser(ownerId, "ROLE_admin", () -> transaction.execute(status -> {
                boolean changed = this.studentService.deactivateStudent(studentId).changed();
                firstChanged.countDown();
                await(allowCommit);
                return changed;
            })));
            try {
                await(firstChanged);
                var second = pool.submit(() -> asUser(ownerId, "ROLE_admin", () -> {
                    secondStarted.countDown();
                    return this.studentService.deactivateStudent(studentId).changed();
                }));
                await(secondStarted);
                assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                allowCommit.countDown();

                assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
                assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
                assertThat(this.studentRepository.findById(studentId).orElseThrow().isEnabled()).isFalse();
            } finally {
                allowCommit.countDown();
            }
        } finally {
            this.studentRepository.deleteById(studentId);
        }
    }

    private Student student(String email) {
        return this.entityManager.createQuery("select s from Student s where s.email = :email", Student.class)
                .setParameter("email", email).getSingleResult();
    }

    private Instructor instructor(String email) {
        return this.entityManager.createQuery("select i from Instructor i where i.email = :email", Instructor.class)
                .setParameter("email", email).getSingleResult();
    }

    private List<Integer> activityIds(Integer studentId) {
        return this.entityManager.createQuery("select a.activityId from Activity a where a.student.id = :studentId", Integer.class)
                .setParameter("studentId", studentId).getResultList();
    }

    private PeerEvaluationAverage reportRow(Integer sectionId, String week, Integer studentId) {
        return this.evaluationService.generateWeeklyPeerEvaluationReportForSection(sectionId, week)
                .getPeerEvaluationAverages().stream().filter(row -> row.getStudentId().equals(studentId))
                .findFirst().orElseThrow();
    }

    private static <T> T asUser(Integer userId, String role, Callable<T> action) throws Exception {
        var context = SecurityContextHolder.createEmptyContext();
        Jwt principal = Jwt.withTokenValue("service-test").header("alg", "RS256").claim("userId", userId).build();
        context.setAuthentication(new TestingAuthenticationToken(principal, null, role));
        SecurityContextHolder.setContext(context);
        try {
            return action.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
