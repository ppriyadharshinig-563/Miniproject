import java.util.ArrayList;

public class DailyTrip {

    private int tripId;
    private String date;
    private CollegeBus bus;

    private ArrayList<Student> assignedStudents;
    private ArrayList<PickupStop> routeStops;

    public DailyTrip(int tripId, String date, CollegeBus bus) {

        this.tripId = tripId;
        this.date = date;
        this.bus = bus;

        assignedStudents = new ArrayList<>();
        routeStops = new ArrayList<>();
    }

    public int getTripId() {
        return tripId;
    }

    public String getDate() {
        return date;
    }

    public CollegeBus getBus() {
        return bus;
    }

    public ArrayList<Student> getAssignedStudents() {
        return assignedStudents;
    }

    public ArrayList<PickupStop> getRouteStops() {
        return routeStops;
    }

    public void assignStudent(Student student) {

        if (bus.canAddStudent()) {

            assignedStudents.add(student);
            bus.addStudent();

            System.out.println(
                student.getName() + " assigned to Bus "
                + bus.getBusNumber()
            );

        } else {

            System.out.println(
                "Bus is full! "
                + student.getName() + " cannot be assigned."
            );
        }
    }

    public void addRouteStop(PickupStop stop) {

        routeStops.add(stop);

        System.out.println(
            stop.getStopName() + " added to route."
        );
    }

    public void displayTrip() {

        System.out.println("\n===== DAILY TRIP =====");

        System.out.println("Trip ID : " + tripId);
        System.out.println("Date    : " + date);
        System.out.println("Bus     : " + bus.getBusNumber());

        System.out.println("\nAssigned Students:");

        for (Student student : assignedStudents) {

            System.out.println(
                student.getStudentId() + " - "
                + student.getName()
            );
        }

        System.out.println("\nRoute Stops:");

        for (PickupStop stop : routeStops) {

            System.out.println(
                stop.getStopName()
            );
        }
    }
}