package team.projectpulse.student;

import team.projectpulse.section.Section;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.system.UserUtils;
import team.projectpulse.system.exception.ObjectNotFoundException;
import team.projectpulse.user.userinvitation.UserInvitationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StudentServiceTest {

    @Mock
    StudentRepository studentRepository;
    @Mock
    SectionRepository sectionRepository;
    @Mock
    UserInvitationService userInvitationService;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    UserUtils userUtils;

    @InjectMocks
    private StudentService studentService;

    List<Student> students;

    @BeforeEach
    void setUp() {
        // Create students
        Student john = new Student("john", "John", "Smith", "j.smith@abc.edu", "123456", true, "student");
        Student eric = new Student("eric", "Eric", "Hudson", "e.hudson@abc.edu", "123456", true, "student");
        Student jerry = new Student("jerry", "Jerry", "Moon", "j.moon@abc.edu", "123456", true, "student");
        Student woody = new Student("woody", "Woody", "Allen", "w.allen@abc.edu", "123456", true, "student");
        Student amanda = new Student("amanda", "Amanda", "Wagner", "a.wagner@abc.edu", "123456", true, "student");
        Student cora = new Student("cora", "Cora", "Manning", "c.manning@abc.edu", "123456", true, "student");
        Student agustin = new Student("agustin", "Agustin", "Freeman", "a.freeman@abc.edu", "123456", true, "student");
        Student mavis = new Student("mavis", "Mavis", "Huber", "m.huber@abc.edu", "123456", true, "student");
        Student mary = new Student("mary", "Mary", "Vargas", "m.vargas@abc.edu", "123456", true, "student");
        Student rosendo = new Student("rosendo", "Rosendo", "Maxwell", "r.maxwell@abc.edu", "123456", true, "student");

        this.students = List.of(john, eric, jerry, woody, amanda, cora, agustin, mavis, mary, rosendo);
    }

    @Test
    void testFindByCriteria() {
        // Given
        Map<String, String> searchCriteria = Map.of("firstName", "john");
        PageImpl expectedPage = new PageImpl(List.of(this.students.get(0)));

        given(this.userUtils.getUserSectionId()).willReturn(2);
        given(this.studentRepository.findAll(any(Specification.class), any(Pageable.class))).willReturn(expectedPage);
        // When
        Page<Student> result = this.studentService.findByCriteria(searchCriteria, PageRequest.of(0, 20));

        // Then
        assertThat(result.getContent().size()).isEqualTo(1);
    }

    @Test
    void testFindStudentById() {
        // Given
        Student john = this.students.get(0);
        given(this.userUtils.getUserId()).willReturn(1);
        given(this.studentRepository.findAccessibleStudent(1, 1, true, false, false)).willReturn(Optional.of(john));

        // When
        Student result = this.studentService.findStudentById(1);

        // Then
        assertThat(result.getFirstName()).isEqualTo("John");
        assertThat(result.getLastName()).isEqualTo("Smith");
    }

    @Test
    void testSaveStudent() {
        // Given
        Student jan = new Student("jan", "Jan", "Mckinney", "j.mckinney@abc.edu", "Abc123456", true, "student");
        doNothing().when(this.userInvitationService).validateUserInvitation(anyString(), anyString(), anyInt(), anyInt(), anyString());
        given(this.sectionRepository.findById(1)).willReturn(Optional.of(new Section()));
        given(this.passwordEncoder.encode(anyString())).willReturn("Encoded Password");
        given(this.studentRepository.save(jan)).willReturn(jan);

        // When
        Student result = this.studentService.saveStudent(jan, 1, 1, "token", "student");

        // Then
        assertThat(result.getFirstName()).isEqualTo("Jan");
        assertThat(result.getLastName()).isEqualTo("Mckinney");
        assertThat(result.getEmail()).isEqualTo("j.mckinney@abc.edu");
        assertThat(result.getRoles()).isEqualTo("student");
        assertThat(result.getSection()).isNotNull();
        assertThat(result.isEnabled()).isEqualTo(true);
    }

    @Test
    void testStudentUpdateOwnInfo() {
        // Given
        Student update = new Student("john", "John (updated)", "Smith (updated)", "j.smith@abc.edu (updated)", "", false, "admin student");
        given(this.userUtils.getUserId()).willReturn(1);
        given(this.studentRepository.findAccessibleStudentForUpdate(1, 1, true, false, false))
                .willReturn(Optional.of(this.students.get(0)));
        given(this.studentRepository.save(any(Student.class))).willReturn(this.students.get(0));

        // When
        Student result = this.studentService.updateStudent(1, update);

        // Then
        assertThat(result.getUsername()).isEqualTo(update.getUsername());
        assertThat(result.getFirstName()).isEqualTo(update.getFirstName());
        assertThat(result.getLastName()).isEqualTo(update.getLastName());
        assertThat(result.getEmail()).isEqualTo(update.getEmail());
        assertThat(result.getRoles()).isNotEqualTo(update.getRoles());
        assertThat(result.isEnabled()).isNotEqualTo(update.isEnabled());
    }

    @Test
    void instructorCannotDeactivateOrChangeProfileThroughGenericUpdate() {
        Student john = this.students.getFirst();
        Student update = new Student("new-username", "New first", "New last", "new@abc.edu", "", false, "student");
        given(this.userUtils.getUserId()).willReturn(2);
        given(this.userUtils.hasRole("ROLE_admin")).willReturn(false);
        given(this.userUtils.hasRole("ROLE_instructor")).willReturn(true);
        given(this.studentRepository.findAccessibleStudentForUpdate(1, 2, true, true, false))
                .willReturn(Optional.of(john));

        assertThatThrownBy(() -> this.studentService.updateStudent(1, update))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Deactivate action");
        assertThat(john.isEnabled()).isTrue();
        assertThat(john.getUsername()).isEqualTo("john");
        assertThat(john.getFirstName()).isEqualTo("John");
        assertThat(john.getLastName()).isEqualTo("Smith");
        assertThat(john.getEmail()).isEqualTo("j.smith@abc.edu");
        verify(this.studentRepository, never()).save(any());
    }

    @Test
    void deactivatesStudentWithoutATeam() {
        Student john = this.students.getFirst();
        Section section = new Section();
        section.addStudent(john);
        given(this.userUtils.getUserId()).willReturn(2);
        given(this.userUtils.hasRole("ROLE_admin")).willReturn(false);
        given(this.userUtils.hasRole("ROLE_instructor")).willReturn(true);
        given(this.studentRepository.findAccessibleStudentForUpdate(1, 2, false, true, false))
                .willReturn(Optional.of(john));
        given(this.studentRepository.save(john)).willReturn(john);

        StudentService.DeactivationResult result = this.studentService.deactivateStudent(1);

        assertThat(result.changed()).isTrue();
        assertThat(result.student().isEnabled()).isFalse();
        assertThat(result.student().getTeam()).isNull();
        assertThat(result.student().getSection()).isSameAs(section);
    }

    @Test
    void alreadyInactiveStudentIsNotSavedAgain() {
        Student john = this.students.getFirst();
        john.setEnabled(false);
        given(this.userUtils.getUserId()).willReturn(2);
        given(this.userUtils.hasRole("ROLE_admin")).willReturn(false);
        given(this.userUtils.hasRole("ROLE_instructor")).willReturn(true);
        given(this.studentRepository.findAccessibleStudentForUpdate(1, 2, false, true, false))
                .willReturn(Optional.of(john));

        StudentService.DeactivationResult result = this.studentService.deactivateStudent(1);

        assertThat(result.changed()).isFalse();
        assertThat(result.student().isEnabled()).isFalse();
        verify(this.studentRepository, never()).save(any());
    }

    @Test
    void studentCannotDeactivateSelfEvenThroughService() {
        given(this.userUtils.getUserId()).willReturn(1);

        assertThatThrownBy(() -> this.studentService.deactivateStudent(1))
                .isInstanceOf(ObjectNotFoundException.class);
        verifyNoInteractions(this.studentRepository);
    }

    @Test
    void testDeleteStudent() {
        // Given
        given(this.studentRepository.findById(1)).willReturn(Optional.of(this.students.get(0)));
        doNothing().when(this.studentRepository).deleteById(1);

        // When
        this.studentService.deleteStudent(1);

        // Then
        verify(this.studentRepository, times(1)).deleteById(1);
    }

}