package com.example.assistant;

class QuestionTooLongException extends RuntimeException {

  QuestionTooLongException(int max) {
    super("Question exceeds the maximum length of " + max + " characters");
  }
}
