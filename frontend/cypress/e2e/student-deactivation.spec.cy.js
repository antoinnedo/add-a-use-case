const student = {
  id: 4,
  username: 'j.smith@abc.edu',
  firstName: 'John',
  lastName: 'Smith',
  email: 'j.smith@abc.edu',
  enabled: true,
  sectionId: 2,
  teamId: 1,
  teamName: 'Team1',
  roles: 'student'
}
const success = (data, message = 'Success') => ({ flag: true, code: 200, data, message })

function visitAs(path, user) {
  // Client-side identity for stubbed requests. The backend suite verifies real bearer authorization.
  const claims = btoa(JSON.stringify({ exp: 4102444800 }))
  cy.visit(path, {
    onBeforeLoad(win) {
      win.localStorage.setItem('token', JSON.stringify({ token: `test.${claims}.test` }))
      win.localStorage.setItem('userInfo', JSON.stringify({ userInfo: user }))
      win.localStorage.setItem('settings', JSON.stringify({ defaultSectionId: 2, defaultCourseId: 1 }))
    }
  })
}

describe('Student deactivation', () => {
  let enabled
  const profileDialog = () => cy.contains('[role=dialog]', 'Edit a student')
  const warningDialog = () => cy.contains('[role=dialog]', 'Deactivate student')
  const confirm = () => cy.get('[data-cy=deactivate-confirm]')

  beforeEach(() => {
    enabled = true
    cy.intercept('POST', '**/api/v1/students/search*', (req) =>
      req.reply(success({ content: [{ ...student, enabled }], number: 0, size: 50, totalElements: 1 }))
    ).as('students')
    cy.intercept('GET', '**/api/v1/students/4', (req) =>
      req.reply(success({ ...student, enabled }))
    ).as('details')
  })

  function openProfile() {
    visitAs('/students', { id: 2, roles: 'instructor', firstName: 'Bill', lastName: 'Gates' })
    cy.wait('@students')
    cy.get('[data-cy=edit-student]').click()
    cy.wait('@details')
    profileDialog().should('be.visible')
  }

  function stubDeactivation(message = 'Student deactivated successfully.') {
    cy.intercept('POST', '**/api/v1/students/4/deactivate', (req) => {
      enabled = false
      req.reply(success({ ...student, enabled }, message))
    }).as('deactivate')
  }

  it('warns about the named student, sends only the dedicated action, and refreshes status (T01, T14)', () => {
    stubDeactivation()
    cy.intercept('PUT', '**/api/v1/students/4').as('profileSave')
    openProfile()
    cy.get('[data-cy=student-status]').should('have.text', 'Enabled')
    cy.get('[data-cy=student-reactivate]').should('not.exist')
    profileDialog().contains('.el-form-item', 'First Name:').find('input').clear().type('Unsaved')
    cy.get('[data-cy=deactivate-open]').click()

    cy.get('[data-cy=deactivate-warning]')
      .should('contain', 'John Smith')
      .and('not.contain', 'Unsaved')
      .and('contain', 'cannot sign in')
      .and('contain', 'existing session')
      .and('contain', 'stays enrolled')
      .and('contain', 'team assignment')
      .and('contain', 'weekly activity reports and peer evaluations')
      .and('contain', 'instructor reports')
      .and('contain', 'submission reminders')
      .and('contain', 'owing submissions')
      .and('contain', 'no longer need to evaluate')
      .and('contain', 'cannot submit or edit evaluations')
    confirm().click()

    cy.wait('@deactivate').its('request.body').should('be.empty')
    cy.wait('@students')
    cy.get('@profileSave.all').should('have.length', 0)
    cy.contains('.el-message--success', 'Student deactivated successfully.').should('be.visible')
    cy.contains('tr', 'John').should('contain', 'Disabled')
    cy.get('[data-cy=student-reactivate]').find('[role=switch]').should('have.attr', 'aria-checked', 'false')
    cy.get('[data-cy=deactivate-open]').should('not.exist')
  })

  it('reports the distinct result when another instructor deactivated after the warning opened (T04)', () => {
    stubDeactivation('Student is already deactivated.')
    openProfile()
    cy.get('[data-cy=deactivate-open]').click()
    cy.then(() => { enabled = false })
    confirm().click()
    cy.wait('@deactivate')
    cy.wait('@students')
    cy.contains('.el-message--success', 'Student is already deactivated.').should('be.visible')
    cy.contains('tr', 'John').should('contain', 'Disabled')
  })

  it('returns to the details without a write on Cancel, close, or Escape (T07)', () => {
    stubDeactivation()
    openProfile()

    const dismiss = [
      () => cy.get('[data-cy=deactivate-cancel]').click(),
      () => warningDialog().find('.el-dialog__headerbtn').click(),
      () => cy.get('body').type('{esc}')
    ]
    dismiss.forEach((close) => {
      cy.get('[data-cy=deactivate-open]').click()
      warningDialog().should('be.visible')
      close()
      cy.get('[data-cy=deactivate-warning]').should('not.be.visible')
      profileDialog().should('be.visible')
    })
    cy.get('@deactivate.all').should('have.length', 0)
    cy.get('.el-message--success').should('not.exist')
  })

  it('blocks duplicate confirmation and permits retry after a failed request (T15)', () => {
    cy.intercept('POST', '**/api/v1/students/4/deactivate', {
      delay: 1200,
      statusCode: 400,
      body: { flag: false, code: 400, message: 'Deactivation failed.' }
    }).as('deactivate')
    openProfile()
    cy.get('[data-cy=deactivate-open]').click()
    confirm().click().should('be.disabled')
    confirm().click({ force: true })
    cy.wait('@deactivate')
    cy.get('@deactivate.all').should('have.length', 1)
    cy.contains('.el-message--error', 'Deactivation failed.').should('be.visible')
    cy.get('.el-message--success').should('not.exist')
    confirm().should('be.enabled')
    cy.get('[data-cy=student-status]').should('have.text', 'Enabled')
  })

  it('keeps the existing profile save and reactivation control (T14)', () => {
    enabled = false
    cy.intercept('PUT', '**/api/v1/students/4', (req) => {
      enabled = req.body.enabled
      req.reply(success({ ...student, ...req.body }))
    }).as('profileSave')
    openProfile()
    cy.get('[data-cy=student-reactivate]').click()
    cy.get('[data-cy=student-save]').click()
    cy.wait('@profileSave').its('request.body.enabled').should('eq', true)
    cy.wait('@students')
    cy.contains('tr', 'John').should('contain', 'Enabled')
  })
})

describe('Peer evaluation submission after deactivation', () => {
  const inactiveStudent = { ...student, id: 5, firstName: 'Eric', lastName: 'Hudson', enabled: false }

  function openSubmission(existing) {
    cy.intercept('GET', '**/api/v1/students/teams/1', success([student, inactiveStudent]))
    cy.intercept('GET', '**/api/v1/sections/2', success({ rubricId: 3 }))
    cy.intercept('GET', '**/api/v1/rubrics/3', success({
      criteria: [{ criterionId: 1, criterion: 'Work', description: 'Quality of work', maxScore: 10 }]
    }))
    cy.intercept('GET', '**/api/v1/evaluations/evaluators/4/week/*', success(existing)).as('evaluations')
    visitAs('/evaluations/submit-evaluations', student)
    cy.wait('@evaluations')
    cy.contains('tr', 'John Smith').should('be.visible')
    cy.contains('tr', 'Eric Hudson').should('not.exist')
  }

  it('omits an inactive teammate from new submission targets (T16)', () => {
    openSubmission([])
    cy.contains('button', /^Submit$/).scrollIntoView().should('be.visible')
  })

  it('keeps edits for active students and never sends an update for an inactive teammate (T16)', () => {
    const evaluation = (target, evaluationId) => ({
      evaluationId,
      evaluateeId: target.id,
      evaluateeName: `${target.firstName} ${target.lastName}`,
      ratings: [{ criterionId: 1, actualScore: 8 }]
    })
    const current = evaluation(student, 10)
    cy.intercept('PUT', '**/api/v1/evaluations/*', success(current)).as('saveEvaluation')
    openSubmission([current, evaluation(inactiveStudent, 11)])
    cy.contains('button', /^Update$/).click()
    cy.wait('@saveEvaluation').its('request.body.evaluateeId').should('eq', 4)
    cy.contains('.el-message--success', 'Peer evaluations updated successfully').should('be.visible')
    cy.get('@saveEvaluation.all').should('have.length', 1)
  })
})
