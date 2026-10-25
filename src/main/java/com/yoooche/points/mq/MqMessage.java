package com.yoooche.points.mq;

/** {@code keys} are space-separated; RocketMQ indexes each one for lookups. */
public record MqMessage(String keys, String payload) {
}
