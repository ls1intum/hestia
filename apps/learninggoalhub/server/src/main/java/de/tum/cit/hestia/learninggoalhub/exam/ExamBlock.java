package de.tum.cit.hestia.learninggoalhub.exam;

/**
 * One block of an exam as submitted by an API consumer (ExamLens). Blocks arrive in exam order:
 * a CONTEXT block applies to every TASK block after it, so later tasks see the accumulated
 * context of all preceding CONTEXT blocks.
 *
 * @param blockId     the consumer's identifier for the block, echoed back in the response.
 * @param blockType   CONTEXT or TASK.
 * @param taskType    the consumer's task-type label (e.g. "singleChoice", "freeText"); only
 *                    meaningful for TASK blocks, passed to the LLM as a hint.
 * @param description the block's text: shared context, or the task statement itself.
 */
public record ExamBlock(String blockId, ExamBlockType blockType, String taskType, String description) {
}
