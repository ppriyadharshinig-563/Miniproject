public class StandardBus extends CollegeBus {

    public StandardBus(int busId, String busNumber, int capacity) {
        super(busId, busNumber, capacity);
    }

    @Override
    public boolean canUseRoad(String roadType) {

        if (roadType.equalsIgnoreCase("Normal")) {
            return true;
        }

        if (roadType.equalsIgnoreCase("Wide")) {
            return true;
        }

        return false;
    }
}