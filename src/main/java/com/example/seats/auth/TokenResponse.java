package com.example.seats.auth;

import java.util.List;

public record TokenResponse(String accessToken, String tokenType, long expiresIn, String userId, List<String> roles) {
}
