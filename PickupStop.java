public class PickupStop {

    private int stopId;
    private String stopName;
    private int travelTime;

    public PickupStop(int stopId, String stopName, int travelTime) {
        this.stopId = stopId;
        this.stopName = stopName;
        this.travelTime = travelTime;
    }

    public int getStopId() {
        return stopId;
    }

    public String getStopName() {
        return stopName;
    }

    public int getTravelTime() {
        return travelTime;
    }

    public void displayStop() {
        System.out.println("-----------------------------");
        System.out.println("Stop ID       : " + stopId);
        System.out.println("Stop Name     : " + stopName);
        System.out.println("Travel Time   : " + travelTime + " minutes");
    }
}