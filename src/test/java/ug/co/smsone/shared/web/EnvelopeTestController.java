package ug.co.smsone.shared.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.error.NotFoundException;

/** Test-classpath-only controller exercising the envelope contract. Not part of main sources. */
@RestController
@RequestMapping("/test")
class EnvelopeTestController {

    record Greeting(String message) {
    }

    record SignupRequest(@NotBlank @Email String email, @NotBlank String name) {
    }

    @GetMapping("/echo")
    Greeting echo() {
        return new Greeting("hello");
    }

    @PostMapping("/signup")
    Greeting signup(@Valid @RequestBody SignupRequest request) {
        return new Greeting("welcome " + request.name());
    }

    @GetMapping("/boom")
    Greeting boom() {
        throw new IllegalStateException("secret-internal-detail");
    }

    @GetMapping("/missing")
    Greeting missing() {
        throw new NotFoundException("Thing 42 does not exist.");
    }
}
