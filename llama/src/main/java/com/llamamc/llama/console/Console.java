package com.llamamc.llama.console;

import com.llamamc.llamaapi.console.IConsole;
import com.llamamc.llamaapi.console.IPrinter;
import com.llamamc.llamaapi.exception.NoTerminalFoundException;
import org.jline.reader.*;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Console extends Thread implements IConsole {
	private final List<String> commands = new ArrayList<>(List.of("info", "version", "help", "stop"));

	private Terminal terminal;
	private Completer completer;
	private LineReader reader;
	private IPrinter printer;

	@Override
	public void run() {
		try {
			terminal = TerminalBuilder.builder().system(true).build();
			completer = (_, line, candidates) -> {
				String buffer = line.line();
				for (String command : commands) {
					if (command.startsWith(buffer)) {
						candidates.add(new Candidate(command));
					}
				}
			};
			reader = LineReaderBuilder.builder().terminal(terminal).completer(completer).history(new DefaultHistory())
					.build();
			if (terminal == null) {
				throw new NoTerminalFoundException("Terminal is null.");
			}
			this.printer = new Printer(terminal.writer(), "Llama » ");
			printer.println("Welcome to Llama - Type 'help' to list commands.", true);
			terminal.flush();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void start() {
		while (true) {
			String line;
			try {
				String userName = System.getProperty("user.name");
                String hostName = System.getenv("HOSTNAME");
				if (userName.isEmpty()) {
                    
				}
				line = reader.readLine("@" + System.getenv("HOSTNAME") + " » ");
			} catch (UserInterruptException e) {
				continue;
			} catch (EndOfFileException e) {
				break;
			}
			if (line == null)
				break;
			line = line.trim();
			if (line.isEmpty())
				continue;
			String[] parts = line.split("\\s+");
			String cmd = parts[0];
			handleCommand(cmd, parts);
			terminal.flush();
		}
		this.exit();
	}

	@Override
	public void handleCommand(String cmd, String[] parts) {
		/*
		 * TODO: Console command handler
		 */
	}

	@Override
	public void exit() {
		this.printer = null;
		this.reader = null;
		this.completer = null;
		this.terminal.flush();
		this.terminal = null;
	}

	@Override
	public IPrinter getPrinter() {
		return printer;
	}
}
