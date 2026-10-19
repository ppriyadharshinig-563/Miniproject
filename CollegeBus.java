public class CollegeBus {

    private int busId;
    private String busNumber;
    private int capacity;
    private int currentStudents;

    public CollegeBus(int busId, String busNumber, int capacity) {

        this.busId = busId;
        this.busNumber = busNumber;
        this.capacity = capacity;
        this.currentStudents = 0;
    }

    public int getBusId() {
        return busId;
    }

    public String getBusNumber() {
        return busNumber;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getCurrentStudents() {
        return currentStudents;
    }

    public boolean canAddStudent() {

        return currentStudents < capacity;
    }

    public void addStudent() {

        if (canAddStudent()) {
            currentStudents++;
        } else {
            System.out.println("Bus is full!");
        }
    }
    public boolean canUseRoad(String roadType) {
    return true;
}

    public void displayBus() {

        System.out.println("-----------------------------");
        System.out.println("Bus ID           : " + busId);
        System.out.println("Bus Number       : " + busNumber);
        System.out.println("Capacity         : " + capacity);
        System.out.println("Current Students : " + currentStudents);
        System.out.println("Available Seats  : " + (capacity - currentStudents));
    }
}