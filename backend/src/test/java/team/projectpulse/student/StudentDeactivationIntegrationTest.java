package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.user.UserService;
import team.projectpulse.user.resetpassword.ResetPasswordToken;
import team.projectpulse.user.resetpassword.ResetPasswordTokenRepository;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StudentDeactivationIntegrationTest extends AbstractIntegrationTest {

    private static final String PROFILE = """
            {"username":"j.smith@abc.edu","firstName":"John","lastName":"Smith","email":"j.smith@abc.edu","enabled":true}
            """;

    @Autowired MockMvc mvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired EntityManager entityManager;
    @Autowired StudentRepository studentRepository;
    @Autowired ResetPasswordTokenRepository resetTokens;
    @Autowired UserService userService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PlatformTransactionManager transactionManager;
    @Value("${api.endpoint.base-url}") String baseUrl;

    @Test
    void assignedInstructorDeactivatesAndExistingStudentTokenStopsWorking() throws Exception {
        String student = login("j.smith@abc.edu");
        String instructor = login("b.gates@abc.edu");
        details(student).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));

        deactivate(instructor, "4").andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.message").value("Student deactivated successfully."))
                .andExpect(jsonPath("$.data.id").value(4))
                .andExpect(jsonPath("$.data.enabled").value(false));
        this.entityManager.flush();
        this.entityManager.clear();

        details(student).andExpect(status().isUnauthorized());
        loginRequest("j.smith@abc.edu").andExpect(status().isUnauthorized());
        deactivate(login("t.cook@abc.edu"), "4").andExpect(status().isForbidden());
        deactivate(instructor, "4").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Student is already deactivated."))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    void unassignedOwnerCanReadEditAndDeactivateStudentInOwnedCourse() throws Exception {
        var section = this.entityManager.find(Student.class, 4).getSection();
        assertThat(section.getInstructors()).contains(section.getCourse().getCourseAdmin());
        var courseOwner = section.getCourse().getCourseAdmin();
        courseOwner.setDefaultSection(null);
        section.getInstructors().remove(courseOwner);
        this.entityManager.flush();
        this.entityManager.clear();
        String owner = login("b.wei@abc.edu");

        this.mvc.perform(put(this.baseUrl + "/instructors/sections/2/default").header(AUTHORIZATION, owner))
                .andExpect(status().isOk());
        this.mvc.perform(post(this.baseUrl + "/students/search").header(AUTHORIZATION, owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"j.smith@abc.edu\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].id").value(4));
        this.mvc.perform(put(this.baseUrl + "/instructors/sections/2/default")
                        .header(AUTHORIZATION, login("t.cook@abc.edu")))
                .andExpect(status().isForbidden());
        details(owner).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(4));
        update(owner).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));
        deactivate(owner, "4").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
        this.mvc.perform(post(this.baseUrl + "/activities/search").header(AUTHORIZATION, owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":\"1\",\"week\":\"2023-W31\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].studentId", hasItem(4)));
        String report = this.baseUrl + "/evaluations/sections/2/week/2023-W31";
        this.mvc.perform(get(report).header(AUTHORIZATION, owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.peerEvaluationAverages[*].studentId", hasItem(4)));
        this.mvc.perform(get(report).header(AUTHORIZATION, login("t.cook@abc.edu")))
                .andExpect(status().isForbidden());
    }

    @Test
    void routeDeniesUnassignedStaffStudentAndAnonymousRequestsWithoutMutation() throws Exception {
        var section = this.entityManager.find(Student.class, 4).getSection();
        assertThat(section.getInstructors().removeIf(instructor -> instructor.getUsername().equals("b.gates@abc.edu"))).isTrue();
        this.entityManager.flush();
        this.entityManager.clear();

        for (String email : new String[]{"b.gates@abc.edu", "t.cook@abc.edu"}) {
            String token = login(email);
            details(token).andExpect(status().isForbidden());
            update(token).andExpect(status().isForbidden());
            deactivate(token, "4").andExpect(status().isForbidden());
        }
        deactivate(login("j.smith@abc.edu"), "4").andExpect(status().isForbidden());
        this.mvc.perform(post(this.baseUrl + "/students/4/deactivate")).andExpect(status().isUnauthorized());
        this.entityManager.clear();
        assertThat(this.entityManager.find(Student.class, 4).isEnabled()).isTrue();
    }

    @Test
    void malformedMissingAndUnresolvableTargetsAreForbidden() throws Exception {
        String admin = login("b.wei@abc.edu");
        deactivate(admin, "bogus").andExpect(status().isForbidden());
        deactivate(admin, "2147483647").andExpect(status().isForbidden());
        this.mvc.perform(post(this.baseUrl + "/students/deactivate").header(AUTHORIZATION, admin))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentWithoutASectionFailsClosed() throws Exception {
        this.entityManager.createNativeQuery("update peer_evaluation_user set section_section_id = null where id = 4")
                .executeUpdate();
        this.entityManager.clear();

        deactivate(login("b.wei@abc.edu"), "4").andExpect(status().isForbidden());
        assertThat(this.studentRepository.existsByIdAndEnabledTrue(4)).isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void resetFromStaleSnapshotDoesNotUndoDeactivation() throws Exception {
        String email = "deactivation-reset-race@abc.edu";
        String newPassword = "different123";
        var transaction = new TransactionTemplate(this.transactionManager);
        var independent = new TransactionTemplate(this.transactionManager);
        independent.setPropagationBehavior(Propagation.REQUIRES_NEW.value());

        Integer id = transaction.execute(status -> {
            Student student = new Student(email, "Reset", "Race", email,
                    this.passwordEncoder.encode("123456"), true, "student");
            student.setSection(this.entityManager.find(Student.class, 4).getSection());
            return this.studentRepository.save(student).getId();
        });
        try {
            String oldToken = login(email);
            String owner = login("b.wei@abc.edu");
            var resetToken = this.resetTokens.save(new ResetPasswordToken(email));
            transaction.executeWithoutResult(status -> {
                // Keep the pre-deactivation entity and snapshot in the reset transaction.
                assertThat(this.entityManager.find(Student.class, id).isEnabled()).isTrue();
                independent.executeWithoutResult(inner -> {
                    try {
                        deactivate(owner, id.toString()).andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.enabled").value(false));
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                });
                this.userService.resetPassword(Map.of(
                        "email", email, "token", resetToken.getToken(), "newPassword", newPassword));
            });

            Student saved = this.studentRepository.findById(id).orElseThrow();
            assertThat(saved.isEnabled()).isFalse();
            assertThat(this.passwordEncoder.matches(newPassword, saved.getPassword())).isTrue();
            this.mvc.perform(get(this.baseUrl + "/students/" + id).header(AUTHORIZATION, oldToken))
                    .andExpect(status().isUnauthorized());
        } finally {
            this.resetTokens.deleteById(email);
            this.studentRepository.deleteById(id);
        }
    }

    private ResultActions details(String token) throws Exception {
        return this.mvc.perform(get(this.baseUrl + "/students/4").header(AUTHORIZATION, token));
    }

    private ResultActions update(String token) throws Exception {
        return this.mvc.perform(put(this.baseUrl + "/students/4").header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(PROFILE));
    }

    private ResultActions deactivate(String token, String id) throws Exception {
        return this.mvc.perform(post(this.baseUrl + "/students/{id}/deactivate", id).header(AUTHORIZATION, token));
    }

    private String login(String email) throws Exception {
        var response = loginRequest(email).andExpect(status().isOk()).andReturn().getResponse();
        return "Bearer " + this.jsonMapper.readTree(response.getContentAsString()).path("data").path("token").asText();
    }

    private ResultActions loginRequest(String email) throws Exception {
        return this.mvc.perform(post(this.baseUrl + "/users/login").with(httpBasic(email, "123456")));
    }
}
