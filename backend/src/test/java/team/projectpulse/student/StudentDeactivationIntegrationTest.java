package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import team.projectpulse.AbstractIntegrationTest;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
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
        section.getInstructors().clear(); // Ownership remains enough when the section has no assigned instructors.
        this.entityManager.flush();
        this.entityManager.clear();
        String owner = login("b.wei@abc.edu");

        details(owner).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(4));
        update(owner).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));
        deactivate(owner, "4").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
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
