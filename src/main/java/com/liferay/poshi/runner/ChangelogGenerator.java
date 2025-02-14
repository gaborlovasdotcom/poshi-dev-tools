/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.poshi.runner;

import com.atlassian.jira.rest.client.api.IssueRestClient;
import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.JiraRestClientFactory;
import com.atlassian.jira.rest.client.api.domain.BasicComponent;
import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.Status;
import com.atlassian.jira.rest.client.api.domain.input.LinkIssuesInput;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;

import com.liferay.poshi.core.util.FileUtil;
import com.liferay.poshi.core.util.RegexUtil;
import com.liferay.poshi.core.util.StringUtil;

import io.atlassian.util.concurrent.Promise;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * @author Kenji Heigel
 */
public class ChangelogGenerator {

	public static final String PORTAL_DIR =
		"/opt/dev/projects/github/liferay-portal";

	public static final String RELEASE_TICKET = "POSHI-187";

	public static void main(String[] args)
		throws GitAPIException, IOException, URISyntaxException {

		File gitWorkDir = new File(PORTAL_DIR);

		String poshiDirPath = "modules/test/poshi";

		String bndPath = poshiDirPath + "/poshi-runner/bnd.bnd";

		String changelogPath = poshiDirPath + "/CHANGELOG.markdown";

		File changelogFile = new File(gitWorkDir, changelogPath);

		Git git = Git.open(gitWorkDir);

		Repository repository = git.getRepository();

		ObjectId newReleaseSHA = repository.resolve(repository.getBranch());

		LogCommand bndLogCommand = git.log();

		bndLogCommand.add(newReleaseSHA);
		bndLogCommand.addPath(bndPath);
		bndLogCommand.setMaxCount(50);

		Iterable<RevCommit> bndCommits = bndLogCommand.call();

		ObjectId lastReleaseSHA = null;

		int i = 1;
		String releaseVersion = "";

		for (RevCommit commit : bndCommits) {
			String content = _getFileContentAtCommit(git, commit, bndPath);

			if (i == 2) {
				Matcher matcher = _bundleVersionPattern.matcher(content);

				if (matcher.find()) {
					releaseVersion = matcher.group(1);
				}
			}

			if (content.contains(_getLastReleasedVersion(changelogFile))) {
				lastReleaseSHA = commit.getId();

				break;
			}

			i++;
		}

		System.out.println("releaseVersion" + releaseVersion);
		System.out.println(
			"lastReleaseSHA.getName()" + lastReleaseSHA.getName());

		LogCommand poshiDirLogCommand = git.log();

		poshiDirLogCommand.addPath(poshiDirPath);
		poshiDirLogCommand.addRange(lastReleaseSHA, newReleaseSHA);

		Iterable<RevCommit> commits = poshiDirLogCommand.call();

		Set<String> tickets = new TreeSet<>();

		for (RevCommit commit : commits) {
			String commitMessage = commit.getFullMessage();

			commitMessage = commitMessage.trim();

			Matcher matcher = _ticketPattern.matcher(commitMessage);

			if (matcher.find()) {
				String ticketID = matcher.group();

				System.out.println(ticketID);

				tickets.add(ticketID);
			}
		}

		JiraRestClientFactory jiraRestClientFactory =
			new AsynchronousJiraRestClientFactory();

		URI uri = new URI("https://issues.liferay.com");

		JiraRestClient jiraRestClient =
			jiraRestClientFactory.createWithBasicHttpAuthentication(
				uri, Authentication.JIRA_USERNAME,
				Authentication.JIRA_PASSWORD);

		String ticketListString = tickets.toString();

		ticketListString = StringUtil.replace(ticketListString, "[", "(");
		ticketListString = StringUtil.replace(ticketListString, "]", ")");

		ticketListString = URLEncoder.encode(ticketListString, "UTF-8");

		System.out.println(
			"https://issues.liferay.com/issues/?jql=key%20in" +
				ticketListString);

		IssueRestClient issueRestClient = jiraRestClient.getIssueClient();

		Issue releaseIssue = _getIssue(issueRestClient, RELEASE_TICKET);

		Status releaseIssueStatus = releaseIssue.getStatus();

		String releaseIssueStatusName = releaseIssueStatus.getName();

		if (releaseIssueStatusName.equals("Closed")) {
			throw new RuntimeException(
				"https://issues.liferay.com/browse/" + RELEASE_TICKET +
					" is closed. Verify correct ticket.");
		}

		Map<String, List<String>> ticketGroups = new TreeMap<>();

		for (String ticketID : tickets) {
			System.out.println(ticketID);

			Issue issue = _getIssue(issueRestClient, ticketID);

			String ticketMessage =
				_getTicketMarkdownURL(ticketID) + " - " + issue.getSummary();

			ticketMessage = ticketMessage.trim();

			LinkIssuesInput linkIssuesInput = new LinkIssuesInput(
				RELEASE_TICKET, ticketID, "Relationship");

			issueRestClient.linkIssue(linkIssuesInput);

			if (ticketID.startsWith("LRCI") || ticketID.startsWith("LRQA")) {
				boolean missingLabel = true;

				for (String label : issue.getLabels()) {
					if (label.startsWith("poshi_")) {
						label = _upperCaseEachWord(
							StringUtil.replace(label, "_", " "));

						label = StringUtil.replace(label, "Poshi ", "");

						if (label.equals("Pql")) {
							label = StringUtil.upperCase(label);
						}

						missingLabel = false;

						if (!ticketGroups.containsKey(label)) {
							ticketGroups.put(
								label,
								new ArrayList<>(Arrays.asList(ticketMessage)));

							break;
						}

						List<String> ticketList = ticketGroups.get(label);

						ticketList.add(ticketMessage);

						break;
					}
				}

				if (missingLabel) {
					System.out.println(
						"Missing poshi label: " + _getTicketURL(ticketID));

					if (!ticketGroups.containsKey("Other")) {
						ticketGroups.put(
							"Other",
							new ArrayList<>(Arrays.asList(ticketMessage)));

						continue;
					}

					List<String> ticketList = ticketGroups.get("Other");

					ticketList.add(ticketMessage);
				}
			}
			else if (ticketID.startsWith("POSHI")) {
				Iterable<BasicComponent> iterable = issue.getComponents();

				Iterator<BasicComponent> iterator = iterable.iterator();

				if ((iterable == null) || !iterator.hasNext()) {
					System.out.println(
						"Missing component: " + _getTicketURL(ticketID));
				}

				for (BasicComponent basicComponent : issue.getComponents()) {
					String componentName = basicComponent.getName();

					if (componentName.equals("Release")) {
						break;
					}

					if (!ticketGroups.containsKey(componentName)) {
						ticketGroups.put(
							componentName,
							new ArrayList<>(Arrays.asList(ticketMessage)));

						break;
					}

					List<String> ticketList = ticketGroups.get(componentName);

					ticketList.add(ticketMessage);
				}
			}
			else {
				if (!ticketGroups.containsKey("Other")) {
					ticketGroups.put(
						"Other", new ArrayList<>(Arrays.asList(ticketMessage)));

					continue;
				}

				List<String> ticketList = ticketGroups.get("Other");

				ticketList.add(ticketMessage);
			}
		}

		System.out.println(_getChangelogPost(ticketGroups, releaseVersion));

		String changeLogText = FileUtil.read(changelogFile);

		changeLogText = changeLogText.replaceFirst(
			"# Poshi Runner Change Log\n",
			_getChangelogText(ticketGroups, releaseVersion));

		Files.write(changelogFile.toPath(), changeLogText.getBytes());

		jiraRestClient.close();
	}

	private static String _getChangelogPost(
		Map<String, List<String>> ticketGroups, String releaseVersion) {

		StringBuilder sb = new StringBuilder();

		sb.append("\n# Release: **[POSHI ");
		sb.append(releaseVersion);
		sb.append("](");
		sb.append(_getTicketURL(RELEASE_TICKET));
		sb.append(")**\n\n");

		for (Map.Entry<String, List<String>> entry : ticketGroups.entrySet()) {
			String label = entry.getKey();

			sb.append("_" + label + "_\n");

			for (String ticketMessage : entry.getValue()) {
				sb.append("* " + ticketMessage + "\n");
			}

			sb.append("\n");
		}

		sb.append("## Additional Notes:\n");
		sb.append("For more release notes click here:\n");
		sb.append("https://github.com/liferay/liferay-portal/blob/master/");
		sb.append("modules/test/poshi-runner/CHANGELOG.markdown");

		return sb.toString();
	}

	private static String _getChangelogText(
		Map<String, List<String>> ticketGroups, String releaseVersion) {

		StringBuilder sb = new StringBuilder();

		sb.append("# Poshi Runner Change Log\n");
		sb.append("\n## " + releaseVersion + "\n");

		for (Map.Entry<String, List<String>> entry : ticketGroups.entrySet()) {
			String label = entry.getKey();

			sb.append("\n### " + label + "\n");

			for (String ticketMessage : entry.getValue()) {
				sb.append("\n* " + ticketMessage);
			}

			sb.append("\n");
		}

		return sb.toString();
	}

	private static String _getFileContentAtCommit(
			Git git, RevCommit commit, String path)
		throws IOException {

		Repository repository = git.getRepository();

		try (TreeWalk treeWalk = TreeWalk.forPath(
				repository, path, commit.getTree())) {

			ObjectLoader objectLoader = repository.open(
				treeWalk.getObjectId(0));

			byte[] bytes = objectLoader.getBytes();

			return new String(bytes, StandardCharsets.UTF_8);
		}
	}

	private static Issue _getIssue(
		IssueRestClient issueRestClient, String ticketID) {

		Promise<Issue> promise = issueRestClient.getIssue(ticketID);

		return promise.claim();
	}

	private static String _getLastReleasedVersion(File changelogFile)
		throws IOException {

		if (_lastReleasedVersion != null) {
			return _lastReleasedVersion;
		}

		BufferedReader b = new BufferedReader(new FileReader(changelogFile));

		String readLine = "";

		while ((readLine = b.readLine()) != null) {
			if (readLine.startsWith("##")) {
				String releaseVersion = RegexUtil.getGroup(
					readLine, "##[\\s]*(.*)", 1);

				releaseVersion = releaseVersion.trim();

				String patchVersion = RegexUtil.getGroup(
					releaseVersion, "[\\d]+\\.[\\d]+\\.([\\d]+)", 1);

				Integer patchVersionInteger = 0;

				try {
					patchVersionInteger = Integer.parseInt(patchVersion);
				}
				catch (NumberFormatException numberFormatException) {
					throw new RuntimeException(numberFormatException);
				}

				patchVersionInteger++;

				String newPatchVersion = patchVersionInteger.toString();

				releaseVersion = releaseVersion.replace(
					patchVersion, newPatchVersion);

				_lastReleasedVersion = releaseVersion;

				return _lastReleasedVersion;
			}
		}

		throw new RuntimeException("Could not find last released version");
	}

	private static String _getTicketMarkdownURL(String ticketID) {
		StringBuilder sb = new StringBuilder();

		sb.append("[");

		sb.append(ticketID);

		sb.append("]");
		sb.append("(");

		sb.append(_getTicketURL(ticketID));

		sb.append(")");

		return sb.toString();
	}

	private static String _getTicketURL(String ticketID) {
		return "https://issues.liferay.com/browse/" + ticketID;
	}

	private static String _upperCaseEachWord(String s) {
		char[] chars = s.toCharArray();

		chars[0] = Character.toUpperCase(chars[0]);

		for (int x = 1; x < chars.length; x++) {
			if (chars[x - 1] == ' ') {
				chars[x] = Character.toUpperCase(chars[x]);
			}
		}

		return new String(chars);
	}

	private static final Pattern _bundleVersionPattern = Pattern.compile(
		"Bundle-Version:[\\s]*(.*)");
	private static String _lastReleasedVersion = null;
	private static final Pattern _ticketPattern = Pattern.compile(
		"(LPS|LRQA|LRCI|POSHI)-[0-9]+");

}