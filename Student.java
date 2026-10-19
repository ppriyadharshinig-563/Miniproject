public class Student {

    private int studentId;
    private String name;
    private String pickupStop;
    private boolean transportAuthorized;
    private boolean present;
    private boolean busRequired;

    public Student(int studentId, String name, String pickupStop,
                   boolean transportAuthorized) {

        this.studentId = studentId;
        this.name = name;
        this.pickupStop = pickupStop;
        this.transportAuthorized = transportAuthorized;

        // Initially
        this.present = false;
        this.busRequired = false;
    }

    // Get Student ID
    public int getStudentId() {
        return studentId;
    }

    // Get Student Name
    public String getName() {
        return name;
    }

    // Get Pickup Stop
    public String getPickupStop() {
        return pickupStop;
    }

    // Check Transport Authorization
    public boolean isTransportAuthorized() {
        return transportAuthorized;
    }

    // Check Attendance
    public boolean isPresent() {
        return present;
    }

    // Check whether bus is required
    public boolean isBusRequired() {
        return busRequired;
    }

    // Set Attendance
    public void setPresent(boolean present) {
        this.present = present;
    }

    // Set Bus Requirement
    public void setBusRequired(boolean busRequired) {
        this.busRequired = busRequired;
    }

    // Display Student Details
    public void displayStudent() {

        System.out.println("-----------------------------");
        System.out.println("Student ID           : " + studentId);
        System.out.println("Name                 : " + name);
        System.out.println("Pickup Stop          : " + pickupStop);
        System.out.println("Transport Authorized : " + transportAuthorized);
        System.out.println("Present              : " + present);
        System.out.println("Bus Required         : " + busRequired);
    }
}