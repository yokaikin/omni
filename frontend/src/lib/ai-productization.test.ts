import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

function read(relativePath: string) {
  return readFileSync(new URL(relativePath, import.meta.url), 'utf8')
}

const home = read('../app/page.tsx')
const header = read('../components/Header.tsx')
const search = read('../app/search/page.tsx')
const activity = read('../app/activity/[id]/page.tsx')
const finder = read('../app/ai/ticket-finder/page.tsx')
const finderContent = read('../components/ai/TicketFinderContent.tsx')
const finderModal = read('../components/ai/TicketFinderModal.tsx')
const finderHost = read('../components/ai/TicketFinderModalHost.tsx')
const finderCopy = read('./ai-ticket-finder.ts')
const copilot = read('../components/customer-service/SupportCopilotPanel.tsx')
const workbench = read('../app/console/customer-service/sessions/page.tsx')

test('uses one modal-first AI ticket finder entry flow across the C-end purchase path', () => {
  assert.doesNotMatch(home, /不会搜？直接告诉我你的需求/)
  assert.match(header, /onClick=\{requestOpenAiTicketFinder\}/)
  assert.doesNotMatch(header, /href="\/ai\/ticket-finder"/)
  assert.match(header, /AI 找票/)
  assert.match(search, /onClick=\{requestOpenAiTicketFinder\}/)
  assert.match(activity, /onClick=\{requestOpenAiTicketFinder\}/)
  assert.match(finderHost, /AI_TICKET_FINDER_OPEN_EVENT/)
  assert.match(finderModal, /role="dialog"/)
  assert.match(finderContent, /interpretAiTicketFinder/)
  assert.match(finderContent, /searchAiTicketFinder/)
})

test('finder modal keeps the confirmed input, loading, clarification, result and reset states', () => {
  assert.match(finderContent, /AI_TICKET_FINDER_QUICK_EXAMPLES/)
  assert.match(finderCopy, /上海周末的演唱会/)
  assert.match(finderCopy, /广州300元以内的话剧/)
  assert.match(finderCopy, /北京近期适合两个人看的演出/)
  assert.match(finderContent, /onClick=\{\(\) => setQuery\(example\)\}/)
  assert.match(finderContent, /event\.key === 'Enter'/)
  assert.match(finderContent, /正在理解你的需求/)
  assert.match(finderContent, /正在为你查找可售票/)
  assert.match(finderContent, /resetFinderState/)
})

test('no-result adjustments clear stale parsed state without submitting', () => {
  assert.match(finderContent, /const handleAdjust = \(\) => \{[\s\S]*resetFinderState\(false\)/)
  assert.doesNotMatch(finderContent, /handleAdjustWithHint/)
})

test('finder maps 502, 503 and 504 to the specified user-facing messages', () => {
  assert.match(finderCopy, /AI 返回结果未通过系统校验，请重新尝试。/)
  assert.match(finderCopy, /AI 找票服务暂时不可用，请稍后再试。/)
  assert.match(finderCopy, /AI 查询时间较长，请稍后重试。/)
})

test('direct finder route reuses the shared content instead of duplicating finder logic', () => {
  assert.match(finder, /TicketFinderContent/)
  assert.doesNotMatch(finder, /interpretAiTicketFinder/)
  assert.doesNotMatch(finder, /searchAiTicketFinder/)
})

test('copilot clearly communicates human review before sending', () => {
  assert.match(copilot, /AI 客服 Copilot/)
  assert.match(copilot, /采用建议/)
  assert.match(copilot, /AI 建议尚未发送/)
  assert.match(copilot, /生成 AI 回复建议/)
  assert.match(workbench, /sendSupportMessage/)
})
