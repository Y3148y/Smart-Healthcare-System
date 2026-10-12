package com.aihospital;

import com.aihospital.shared.model.Models.Appointment;
import com.aihospital.shared.model.Models.Doctor;
import com.aihospital.catalog.infrastructure.mybatis.SlotMapper;
import com.aihospital.shared.security.JwtService;
import com.aihospital.booking.application.SimulationBookingService;
import com.aihospital.booking.application.BookingApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SimulationBookingTests {
    @Autowired SimulationBookingService booking;
    @Autowired BookingApplicationService bookingApplication;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SlotMapper slots;
    private final JwtService jwt = new JwtService();

    private String token(String patient) { return "Bearer " + jwt.issue(patient, "PATIENT"); }

    @Test void missingOrBlankBookingIdentityCannotBypassSessionGateOrDeductSlots() throws Exception {
        String patient = "invalid-booking-" + UUID.randomUUID();
        Doctor before = booking.doctors("骨科").get(0);
        for (Map<String, String> invalid : List.of(
                Map.of("doctorId", before.id()),
                Map.of("doctorId", before.id(), "sessionId", " "),
                Map.of("sessionId", "walk-in"),
                Map.of("doctorId", " ", "sessionId", "walk-in"),
                Map.of("doctorId", before.id(), "sessionId", "x".repeat(101)))) {
            var body = new java.util.HashMap<>(invalid);
            body.put("idempotencyKey", UUID.randomUUID().toString());
            mvc.perform(post("/api/appointments").header("Authorization", token(patient))
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(before.remaining(), booking.doctors("骨科").stream()
                .filter(d -> d.id().equals(before.id())).findFirst().orElseThrow().remaining());
        assertTrue(booking.appointments(patient).isEmpty());
    }

    @Test
    void bookingPersistsDecrementsOnceAndIsPrivate() throws Exception {
        String patient = "booking-" + UUID.randomUUID();
        String other = "other-" + UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        Doctor before = booking.doctors("骨科").get(0);
        String body = json.writeValueAsString(Map.of("doctorId", before.id(), "sessionId", "walk-in", "idempotencyKey", key));

        String first = mvc.perform(post("/api/appointments").header("Authorization", token(patient))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("模拟预约"))
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/appointments").header("Authorization", token(patient))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertEquals(json.readTree(first).path("id"), json.readTree(second).path("id"));
        assertEquals(json.readTree(first), json.readTree(second), "Receipt replay retains timestamp precision and all fields");
        assertEquals(before.remaining() - 1, booking.doctors("骨科").get(0).remaining());
        List<Appointment> records = booking.appointments(patient);
        assertEquals(1, records.size());
        mvc.perform(get("/api/appointments").header("Authorization", token(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/appointments").header("Authorization", token(patient))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("doctorId", before.id(), "sessionId", "walk-in"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentRequestsCannotOversellOneRemainingSlot() throws Exception {
        Doctor doctor = booking.doctors("全科医学科").get(0);
        slots.setRemaining(doctor.id(), doctor.date(), 1);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> attemptBooking(start, doctor, "concurrent-a-" + UUID.randomUUID()));
            var second = pool.submit(() -> attemptBooking(start, doctor, "concurrent-b-" + UUID.randomUUID()));
            start.countDown();
            int successes = (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(0, booking.doctors("全科医学科").get(0).remaining());
        } finally {
            pool.shutdownNow();
            slots.setRemaining(doctor.id(), doctor.date(), doctor.remaining());
        }
    }

    @Test
    void concurrentSameKeyReplaysOneReceiptAndConsumesOneSlot() throws Exception {
        Doctor doctor = booking.doctors("骨科").get(0);
        int initial = doctor.remaining();
        slots.setRemaining(doctor.id(), doctor.date(), 2);
        String patient = "same-key-" + UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                start.await();
                return bookingApplication.book(doctor.id(), "walk-in", patient, key);
            });
            var second = pool.submit(() -> {
                start.await();
                return bookingApplication.book(doctor.id(), "walk-in", patient, key);
            });
            start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, booking.appointments(patient).size());
            assertEquals(1, slots.remaining(doctor.id(), doctor.date()));
        } finally {
            pool.shutdownNow();
            slots.setRemaining(doctor.id(), doctor.date(), initial);
        }
    }

    @Test
    void concurrentSameKeyWithDifferentDoctorsCannotConsumeBothSlots() throws Exception {
        Doctor firstDoctor = booking.doctors("骨科").get(0);
        Doctor secondDoctor = booking.doctors("全科医学科").get(0);
        int firstInitial = firstDoctor.remaining();
        int secondInitial = secondDoctor.remaining();
        slots.setRemaining(firstDoctor.id(), firstDoctor.date(), 2);
        slots.setRemaining(secondDoctor.id(), secondDoctor.date(), 2);
        String patient = "conflicting-key-" + UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> attemptBookingWithKey(start, firstDoctor.id(), patient, key));
            var second = pool.submit(() -> attemptBookingWithKey(start, secondDoctor.id(), patient, key));
            start.countDown();
            assertEquals(1, (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1, booking.appointments(patient).size());
            assertEquals(3, slots.remaining(firstDoctor.id(), firstDoctor.date())
                    + slots.remaining(secondDoctor.id(), secondDoctor.date()));
        } finally {
            pool.shutdownNow();
            slots.setRemaining(firstDoctor.id(), firstDoctor.date(), firstInitial);
            slots.setRemaining(secondDoctor.id(), secondDoctor.date(), secondInitial);
        }
    }

    @Test
    void failedClaimAndSlotUpdateRollBackSoSameKeyCanBeRetried() {
        Doctor doctor = booking.doctors("骨科").get(0);
        int initial = doctor.remaining();
        String patient = "retry-key-" + UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        try {
            slots.setRemaining(doctor.id(), doctor.date(), 0);
            var full = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> bookingApplication.book(doctor.id(), "walk-in", patient, key));
            assertEquals(409, full.getStatusCode().value());
            assertTrue(booking.appointments(patient).isEmpty());
            slots.setRemaining(doctor.id(), doctor.date(), 1);
            Appointment created = bookingApplication.book(doctor.id(), "walk-in", patient, key);
            assertEquals(created.id(), bookingApplication.book(doctor.id(), "walk-in", patient, key).id());
            assertEquals(0, slots.remaining(doctor.id(), doctor.date()));
            assertEquals(1, booking.appointments(patient).size());
        } finally {
            slots.setRemaining(doctor.id(), doctor.date(), initial);
        }
    }

    private boolean attemptBookingWithKey(CountDownLatch start, String doctorId, String patient, String key)
            throws InterruptedException {
        start.await();
        try {
            bookingApplication.book(doctorId, "walk-in", patient, key);
            return true;
        } catch (org.springframework.web.server.ResponseStatusException conflict) {
            assertEquals(409, conflict.getStatusCode().value());
            return false;
        }
    }

    private boolean attemptBooking(CountDownLatch start, Doctor doctor, String patient) throws InterruptedException {
        start.await();
        try {
            booking.book(doctor.id(), "walk-in", patient, UUID.randomUUID().toString());
            return true;
        } catch (org.springframework.web.server.ResponseStatusException full) {
            return false;
        }
    }
}
