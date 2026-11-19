package vn.edu.hcmut.vinabeef.health.adapter.web;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import vn.edu.hcmut.vinabeef.health.domain.HealthException;

@RestControllerAdvice
public class HealthExceptionHandler {
    @ExceptionHandler(HealthException.class)
    public ResponseEntity<ProblemResponse> healthProblem(HealthException exception) {
        return ResponseEntity.status(exception.httpStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemResponse.from(exception.httpStatus(), exception.code(),
                        exception.getMessage(), exception.receipt()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ProblemResponse> invalidRequest(Exception exception) {
        return ResponseEntity.unprocessableEntity()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemResponse.from(422, "INVALID_INPUT", "The request is not valid.", null));
    }
}
