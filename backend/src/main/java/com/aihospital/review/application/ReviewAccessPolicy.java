package com.aihospital.review.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Explicit deployment grants to existing authenticated ADMIN subjects; empty denies all text access. */
@Component
public final class ReviewAccessPolicy {
    private final Set<String> subjects;
    public ReviewAccessPolicy(@Value("${review.authorized-subjects:}") String subjects) {
        this.subjects = Arrays.stream(subjects.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
    public boolean mayRead(String subject) { return subject != null && subjects.contains(subject); }
}
