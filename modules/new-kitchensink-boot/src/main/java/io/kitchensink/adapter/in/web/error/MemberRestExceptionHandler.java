package io.kitchensink.adapter.in.web.error;

import java.util.LinkedHashMap;
import java.util.Map;

import io.kitchensink.adapter.in.web.MemberRestController;
import io.kitchensink.member.domain.DuplicateMemberEmailException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = MemberRestController.class)
public class MemberRestExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validationFailed(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(errors);
    }

    @ExceptionHandler(DuplicateMemberEmailException.class)
    ResponseEntity<Map<String, String>> duplicateEmail(DuplicateMemberEmailException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("email", exception.getMessage()));
    }
}
