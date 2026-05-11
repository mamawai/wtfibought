package com.mawai.wiibservice.agent.quant.domain.output;

import java.util.List;

public record ReflectionResponse(
        List<ReflectionLessonResponse> lessons
) {
}
