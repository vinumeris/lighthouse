package lighthouse;

import com.google.bitcoin.protocols.payments.PaymentProtocolException;
import lighthouse.protocol.LHProtos;
import lighthouse.protocol.Project;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

import static java.lang.String.format;

public class CrowdfundTool {
    public static void main(String[] args) {
        if (args.length == 0) {
            // TODO: Help text here.
            System.err.println("Must specify arguments");
            return;
        }

        switch (args[0]) {
            case "show-project":
                if (args.length < 2) {
                    System.err.println("Specify file name of project to show.");
                    return;
                }
                showProject(args[1]);
                break;
        }
    }

    private static void showProject(String filename) {
        try (InputStream stream = Files.newInputStream(Paths.get(filename))) {
            LHProtos.Project proto = LHProtos.Project.parseFrom(stream);
            Project project = new Project(proto);
            System.out.println(project);
        } catch (IOException e) {
            System.err.println(format("Could not open project file %s: %s", filename, e.getMessage()));
        } catch (PaymentProtocolException e) {
            e.printStackTrace();
        }
    }
}
