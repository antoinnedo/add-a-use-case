<template>
  <el-form
    ref="studentForm"
    :model="studentBeingEdited"
    :disabled="!loadedStudent || saving || deactivating"
    :rules="rules"
    label-width="110px"
    style="padding-right: 30px"
    label-position="left"
  >
    <el-form-item label="Id:">
      <el-input v-model="studentBeingEdited.id" disabled></el-input>
    </el-form-item>
    <el-form-item label="Username:" prop="username">
      <el-input v-model="studentBeingEdited.username"></el-input>
    </el-form-item>
    <el-form-item v-if="loadedStudent" label="Status:">
      <el-tag v-if="loadedStudent.enabled" type="success" data-cy="student-status">Enabled</el-tag>
      <el-switch
        v-else
        v-model="studentBeingEdited.enabled"
        data-cy="student-reactivate"
        inline-prompt
        active-text="Enabled"
        inactive-text="Disabled"
        style="--el-switch-on-color: #13ce66; --el-switch-off-color: #ff4949"
      />
    </el-form-item>
    <el-form-item label="First Name:" prop="firstName">
      <el-input v-model="studentBeingEdited.firstName"></el-input>
    </el-form-item>
    <el-form-item label="Last Name:" prop="lastName">
      <el-input v-model="studentBeingEdited.lastName"></el-input>
    </el-form-item>
    <el-form-item label="Email:" prop="email">
      <el-input v-model="studentBeingEdited.email"></el-input>
    </el-form-item>
    <el-form-item label="Team:" prop="teamName">
      <el-input v-model="studentBeingEdited.teamName" disabled></el-input>
    </el-form-item>
    <div style="display: flex; justify-content: end">
      <el-button
        v-if="loadedStudent?.enabled"
        type="danger"
        plain
        data-cy="deactivate-open"
        @click="deactivationVisible = true"
      >
        Deactivate
      </el-button>
      <el-button @click="$emit('close-dialog')">Cancel</el-button>
      <el-button type="primary" data-cy="student-save" @click="updateExistingStudent">
        Confirm
      </el-button>
    </div>
  </el-form>
  <el-dialog
    v-model="deactivationVisible"
    title="Deactivate student"
    append-to-body
    :show-close="!deactivating"
    :close-on-press-escape="!deactivating"
    :close-on-click-modal="!deactivating"
  >
    <div data-cy="deactivate-warning">
      <p>Deactivate {{ loadedStudent?.firstName }} {{ loadedStudent?.lastName }}?</p>
      <ul>
        <li>The student cannot sign in or continue using the app with an existing session.</li>
        <li>The student stays enrolled in the section and keeps any team assignment.</li>
        <li>
          Existing weekly activity reports and peer evaluations stay available in instructor
          reports.
        </li>
        <li>The student will not receive submission reminders or appear as owing submissions.</li>
        <li>
          Teammates no longer need to evaluate this student and cannot submit or edit evaluations of
          this student.
        </li>
      </ul>
    </div>
    <template #footer>
      <el-button
        :disabled="deactivating"
        data-cy="deactivate-cancel"
        @click="deactivationVisible = false"
      >
        Cancel
      </el-button>
      <el-button
        type="danger"
        :loading="deactivating"
        :disabled="deactivating"
        data-cy="deactivate-confirm"
        @click="confirmDeactivation"
      >
        Confirm
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus'
import type { Student } from '@/apis/student/types'
import type { FormInstance } from 'element-plus'
import { deactivateStudent, findStudentById, updateStudent } from '@/apis/student'
import { ref, onMounted } from 'vue'

const { studentId } = defineProps<{ studentId: number }>()
const emit = defineEmits(['close-dialog', 'student-changed'])
const loadedStudent = ref<Student>()
const deactivationVisible = ref(false)
const deactivating = ref(false)
const saving = ref(false)

// Note that the structure of the userData is NOT the same as the User interface since the roles are stored as an array
const studentBeingEdited = ref<Student>({
  // Data of the user being added or edited
  id: NaN,
  username: '',
  enabled: false,
  firstName: '',
  lastName: '',
  email: ''
})

const studentForm = ref<FormInstance>() // Form reference

// Validation rules
const rules = {
  username: [
    { required: true, message: 'Please input your email as your username', trigger: 'blur' },
    {
      pattern: /^[^\s@]+@[^\s@]+\.[^\s@]+$/,
      message: 'Enter your email as username.',
      trigger: 'blur'
    }
  ],
  firstName: [
    { required: true, message: 'Please provide the first name of the user.', trigger: 'blur' }
  ],
  lastName: [
    { required: true, message: 'Please provide the last name of the user.', trigger: 'blur' }
  ],
  email: [
    { required: true, message: 'Please provide the email of the user.', trigger: 'blur' },
    { type: 'email', message: 'Please provide a valid email.', trigger: 'blur' }
  ]
}

function loadDetails(student: Student) {
  loadedStudent.value = student
  studentBeingEdited.value = { ...student }
}

onMounted(async () => {
  try {
    const result = await findStudentById(studentId)
    loadDetails(result.data)
  } catch {
    // The shared request handler presents the failure.
  }
})

async function confirmDeactivation() {
  if (!loadedStudent.value?.enabled || deactivating.value || saving.value) return
  deactivating.value = true
  try {
    const result = await deactivateStudent(studentId)
    if (!result.flag) {
      ElMessage.error(result.message)
      return
    }
    loadDetails(result.data)
    deactivationVisible.value = false
    ElMessage.success(result.message)
    emit('student-changed')
  } catch {
    // The shared request handler presents the failure.
  } finally {
    deactivating.value = false
  }
}

async function updateExistingStudent() {
  if (!loadedStudent.value || saving.value || deactivating.value) return
  saving.value = true
  try {
    await studentForm.value!.validate()
    const { id, username, enabled, firstName, lastName, email } = studentBeingEdited.value
    const reactivate = loadedStudent.value.enabled === false && enabled === true
    const result = await updateStudent(
      { id, username, enabled, firstName, lastName, email },
      reactivate
    )
    if (!result.flag) {
      ElMessage.error(result.message)
      return
    }
    ElMessage.success('Student updated successfully')
    emit('close-dialog')
  } catch {
    // Form validation and the shared request handler present their respective failures.
  } finally {
    saving.value = false
  }
}
</script>
