<template>
  <el-card class="page-container">
    <template #header>
      <div class="header">
        <span
          >Submit Peer Evaluations for week:
          <el-tag type="primary" style="font-size: 14px; font-weight: bold">{{
            previousWeekRange
          }}</el-tag></span
        >
      </div>
      <div style="margin-top: 10px">
        <span>
          The default score for each criterion is 10. You can change it to a value between 0 and 10.
        </span>
        <!-- Remind the user that "You have NOT submitted the evaluation" -->
        <el-tag v-if="isNewSubmission" type="danger" style="font-size: 14px; font-weight: bold"
          >You have NOT submitted the evaluation. Click the "Submit" button.</el-tag
        >
      </div>
    </template>
    <!-- Evaluation Table, adding height can fix the table header -->
    <el-table
      :data="peerEvalutions"
      style="width: 100%"
      stripe
      border
      height="600"
      v-loading="loading"
      scrollbar-always-on
    >
      <el-table-column
        label="Student Name"
        prop="evaluateeName"
        min-width="100"
        fixed
      ></el-table-column>
      <el-table-column v-for="criterion in criteria" :key="criterion.criterionId" min-width="150">
        <template #header>
          <el-tooltip effect="dark" :content="criterion.criterion" placement="top">
            <span>{{ criterion.description }}</span>
          </el-tooltip>
        </template>
        <template #default="{ row }">
          <div style="display: flex; justify-content: center; align-items: center">
            <el-input-number
              v-model="
                row.ratings.find((r: Rating) => r.criterionId === criterion.criterionId).actualScore
              "
              :min="0"
              :max="criterion.maxScore"
              :step="1"
              controls-position="right"
              style="width: 80px"
            />
          </div>
        </template>
      </el-table-column>
      <el-table-column min-width="200px">
        <template #header>
          <el-tooltip
            effect="dark"
            content="Public comments are available to the evaluatee."
            placement="top"
          >
            <span>Public Comment</span>
          </el-tooltip>
        </template>
        <template #default="{ row }">
          <el-input
            v-model="row.publicComment"
            type="textarea"
            :rows="5"
            placeholder="Enter public comment"
            max-length="255"
            show-word-limit
          />
        </template>
      </el-table-column>
      <el-table-column min-width="200px">
        <template #header>
          <el-tooltip
            effect="dark"
            content="Private comments are ONLY available to the instructor."
            placement="top"
          >
            <span>Private Comment</span>
          </el-tooltip>
        </template>
        <template #default="{ row }">
          <el-input
            v-model="row.privateComment"
            type="textarea"
            :rows="5"
            placeholder="Enter private comment"
            max-length="255"
            show-word-limit
          />
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="No data is available." />
      </template>
    </el-table>
    <!-- submit button on the right side-->
    <div style="display: flex; justify-content: flex-end">
      <el-button
        :type="isNewSubmission ? 'primary' : 'warning'"
        :disabled="!peerEvalutions.length"
        :loading="isLoading"
        @click="savePeerEvaluations"
      >{{ isNewSubmission ? 'Submit' : 'Update' }}</el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { findStudentsByTeamId } from '@/apis/student'
import type { Student, FindStudentByTeamIdResponse } from '@/apis/student/types'
import { findSectionById } from '@/apis/section'
import { findRubricById } from '@/apis/rubric'
import type { FindRubricByIdResponse, Criterion } from '@/apis/rubric/types'
import { getPreviousWeek, getPreviousWeekRange } from '@/utils/week'
import { useUserInfoStore } from '@/stores/userInfo'
import type { FindSectionByIdResponse } from '@/apis/section/types'
import {
  submitEvaluation,
  getEvaluationsByEvaluatorIdAndWeek,
  updateEvaluation,
  sendPeerEvaluationSubmissionConfirmationEmail
} from '@/apis/evaluation'
import { ElMessage } from 'element-plus'
import type {
  FetchPeerEvaluationsResponse,
  PeerEvaluation,
  Rating
} from '@/apis/evaluation/types'

const previousWeek = ref<string>('')
const previousWeekRange = ref<string>('')

const peerEvalutions = ref<PeerEvaluation[]>([])

const loading = ref<boolean>(false)

const isNewSubmission = computed(() => peerEvalutions.value.some((row) => row.evaluationId == null))

const teamId = ref<number>(NaN) // Team ID of the student

const students = ref<Student[]>([]) // Students on the same team
const sectionId = ref<number>() // Section ID of the team
const criteria = ref<Criterion[]>([]) // Criteria used for peer evaluations
const isLoading = ref(false)

const userInfoStore = useUserInfoStore() // Need team ID to search for students

// Load data when the component is mounted
onMounted(async () => {
  previousWeek.value = getPreviousWeek()
  previousWeekRange.value = getPreviousWeekRange()
  teamId.value = (userInfoStore.userInfo as Student).teamId as number
  if (!teamId.value) {
    ElMessage.error('You have not been assigned a team yet.')
    return
  }
  await loadStudents()
  sectionId.value = (userInfoStore.userInfo as Student).sectionId
  await loadCriteria()
  loading.value = true
  try {
    await prepareStartingPeerEvaluations()
  } finally {
    loading.value = false
  }
})

// Load activities based on the search criteria
async function loadStudents() {
  // On the back end, the page number starts from 0, so subtract 1 here
  const result: FindStudentByTeamIdResponse = await findStudentsByTeamId(teamId.value)
  students.value = result.data.filter((student) => student.enabled)
}

// Load criteria information
async function loadCriteria() {
  const sectionResult: FindSectionByIdResponse = await findSectionById(sectionId.value as number)
  const rubricResult: FindRubricByIdResponse = await findRubricById(
    sectionResult.data.rubricId as number
  )
  criteria.value = rubricResult.data.criteria!
}

// Create starting peer evaluations
async function prepareStartingPeerEvaluations() {
  // Get the evaluations for the current week
  const result: FetchPeerEvaluationsResponse = await getEvaluationsByEvaluatorIdAndWeek(
    (userInfoStore.userInfo as Student).id as number,
    previousWeek.value
  )

  const savedByEvaluatee = new Map(result.data.map((row) => [row.evaluateeId, row]))
  peerEvalutions.value = students.value.map((student) => savedByEvaluatee.get(student.id as number) ?? ({
    week: previousWeek.value,
    evaluatorId: (userInfoStore.userInfo as Student).id as number,
    evaluateeId: student.id as number,
    evaluateeName: `${student.firstName} ${student.lastName}`,
    ratings: criteria.value.map((criterion) => ({
      criterionId: criterion.criterionId as number,
      criterion: criterion.criterion,
      actualScore: Math.min(10, criterion.maxScore)
    })),
    publicComment: '',
    privateComment: ''
  }))
}

async function savePeerEvaluations() {
  if (isLoading.value || !peerEvalutions.value.length) return

  const hasNewRows = isNewSubmission.value
  isLoading.value = true
  try {
    // Store each response immediately so a retry updates rows already created.
    for (const [index, evaluation] of peerEvalutions.value.entries()) {
      const result = evaluation.evaluationId == null
        ? await submitEvaluation(evaluation)
        : await updateEvaluation(evaluation)
      peerEvalutions.value[index] = result.data
    }
    ElMessage.success(hasNewRows
      ? 'Peer evaluations submitted successfully'
      : 'Peer evaluations updated successfully')

    if (hasNewRows) {
      await sendPeerEvaluationSubmissionConfirmationEmail(previousWeekRange.value)
      ElMessage.success('A confirmation email has been sent to you.')
    }
  } finally {
    isLoading.value = false
  }
}
</script>

<style lang="scss" scoped>
.page-container {
  min-height: 100%;
  box-sizing: border-box;
  .header {
    display: flex;
    align-items: center;
    gap: 20px;
  }
}
</style>
