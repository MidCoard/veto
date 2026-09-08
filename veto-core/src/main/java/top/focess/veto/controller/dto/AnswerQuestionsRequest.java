package top.focess.veto.controller.dto;

import java.util.Map;
import org.jspecify.annotations.NonNull;

public record AnswerQuestionsRequest(Map<@NonNull String, @NonNull String> answers) {}
