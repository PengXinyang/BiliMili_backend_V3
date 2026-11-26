package com.bilimili.message;

import java.time.Instant;

public record ChatMessage(long id, int postId, int acceptId, String content, int withdraw, Instant time, boolean read) { }
