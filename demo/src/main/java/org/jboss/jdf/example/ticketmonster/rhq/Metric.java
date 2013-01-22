package org.jboss.jdf.example.ticketmonster.rhq;

/**
 * A single numeric value to send to the server
 * @author Heiko W. Rupp
 */
public class Metric {

    /** Name of the metric as found in the plugin descriptor */
    String name;

    /** Time when the metric was created in milliseconds since the epoch */
    long timeStamp;

    /** The numerical value */
    Double value;

    public Metric() {
    }

    public Metric(String name, long timeStamp, Double value) {
        this.name = name;
        this.timeStamp = timeStamp;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getTimeStamp() {
        return timeStamp;
    }

    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }

    public Double getValue() {
        return value;
    }

    public void setValue(Double value) {
        this.value = value;
    }
}
