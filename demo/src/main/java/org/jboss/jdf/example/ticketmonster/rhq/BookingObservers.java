package org.jboss.jdf.example.ticketmonster.rhq;

import org.jboss.jdf.example.ticketmonster.model.Booking;
import org.jboss.jdf.example.ticketmonster.monitor.client.shared.qualifier.Created;

import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;

import static javax.enterprise.event.TransactionPhase.AFTER_SUCCESS;

public class BookingObservers {

    @Inject
    RhqClient rhqClient;

    public void onBookingCreated(@Observes(during = AFTER_SUCCESS) @Created Booking booking) {
        List<Metric> metrics = new ArrayList<Metric>(2);
        Metric m = new Metric("tickets", System.currentTimeMillis(), (double) booking.getTickets().size());
        metrics.add(m);
        m = new Metric("price", System.currentTimeMillis(), (double) booking.getTotalTicketPrice());
        metrics.add(m);

        rhqClient.sendMetrics(metrics);
    }
}
