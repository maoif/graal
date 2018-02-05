/*
 * Copyright (c) 2017, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.server;

import static com.oracle.svm.hosted.server.NativeImageBuildServer.PORT_PREFIX;
import static com.oracle.svm.hosted.server.NativeImageBuildServer.extractArg;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.oracle.shadowed.com.google.gson.Gson;
import com.oracle.svm.hosted.server.SubstrateServerMessage.ServerCommand;

public class NativeImageBuildClient {

    public static final String COMMAND_PREFIX = "-command=";
    public static final int EXIT_FAIL = -1;
    public static final int EXIT_SUCCESS = 0;

    private static void usage(Consumer<String> out) {
        out.accept("Usage:");
        out.accept(String.format("  java -cp <svm_jar_path> " + NativeImageBuildClient.class.getName() + " %s<command> [%s<port_number>] [<command_arguments>]", COMMAND_PREFIX,
                        PORT_PREFIX));
    }

    public static int run(String[] argsArray, Consumer<String> out, Consumer<String> err) {
        Consumer<String> outln = s -> out.accept(s + "\n");
        final List<String> args = new ArrayList<>(Arrays.asList(argsArray));
        if (args.size() < 1) {
            usage(outln);
            return EXIT_FAIL;
        } else if (args.size() == 1 && (args.get(0).equals("--help"))) {
            usage(outln);
            return EXIT_SUCCESS;
        }

        final Optional<String> command = extractArg(args, COMMAND_PREFIX).map(arg -> arg.substring(COMMAND_PREFIX.length()));
        final Optional<Integer> port = NativeImageBuildServer.extractPort(args);

        if (port.isPresent() && command.isPresent()) {
            return sendRequest(command.get(), String.join(" ", args), port.get(), out, err);
        } else {
            usage(outln);
            return EXIT_FAIL;
        }
    }

    private static int sendRequest(String command, String payload, int port, Consumer<String> out, Consumer<String> err) {
        Consumer<String> outln = s -> out.accept(s + "\n");
        Consumer<String> errln = s -> out.accept(s + "\n");

        try (
                        Socket svmClient = new Socket((String) null, port);
                        OutputStreamWriter os = new OutputStreamWriter(svmClient.getOutputStream());
                        BufferedReader is = new BufferedReader(new InputStreamReader(svmClient.getInputStream()))) {
            SubstrateServerMessage.send(new SubstrateServerMessage(command, payload), os);
            String line;
            switch (command) {
                case "version":
                    line = is.readLine();
                    if (line != null) {
                        SubstrateServerMessage response = new Gson().fromJson(line, SubstrateServerMessage.class);
                        outln.accept(response.payload);
                    }
                    break;
                default: {
                    while ((line = is.readLine()) != null) {
                        SubstrateServerMessage serverCommand = new Gson().fromJson(line, SubstrateServerMessage.class);
                        Consumer<String> selectedConsumer = null;
                        switch (serverCommand.command) {
                            case WRITE_OUT:
                                selectedConsumer = out;
                                break;
                            case WRITE_ERR:
                                selectedConsumer = err;
                                break;
                            case SEND_STATUS:
                                /* Exit with exit status sent by server */
                                return Integer.valueOf(serverCommand.payload);
                            default:
                                throw new RuntimeException("Invalid command sent by the image build server: " + serverCommand.command);
                        }
                        if (selectedConsumer != null) {
                            selectedConsumer.accept(serverCommand.payload);
                        }
                    }
                    /* Report failure if communication does not end with ExitStatus */
                    return EXIT_FAIL;
                }
            }
        } catch (IOException e) {
            if (!ServerCommand.GET_VERSION.toString().equals(command)) {
                errln.accept("Could not connect to image build server running on port " + port);
                errln.accept("Underlying exception: " + e);
            }
            return EXIT_FAIL;
        }
        return EXIT_SUCCESS;
    }

    public static void main(String[] argsArray) {
        System.exit(run(argsArray, System.out::print, System.err::print));
    }
}
