package top.focess.veto.builtin.group;

import java.util.*;
import org.jspecify.annotations.NonNull;

public record Inspection(
        @NonNull GroupSnapshot group, @NonNull List<@NonNull BlackboardMessage> messages) {}
