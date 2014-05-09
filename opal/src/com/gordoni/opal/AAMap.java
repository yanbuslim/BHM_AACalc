package com.gordoni.opal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

class AAMap
{
        protected BaseScenario scenario;
        protected Config config;

        public MapPeriod[] map;

        // We interpolate metrics when generating.
        // Prior to doing this we ran into artifacts.
        // For instance if we withdraw 100,000 per year, and have a bucket pf appear at 100,015 then the submetrics for
        // that bucket are associated with 15.  Unfortunately 15 is much smaller than the zero bucket size, and so all
        // asset allocations/contributions look the same.  This would cause always donate to get set on low p sizes.
        // Fixing this has allowed us to increase the zero bucket size by a factor of 50.

        public MapElement lookup_interpolate(double[] p, int period)
        {
	        double[] li_dbucket = new double[scenario.start_p.length];
		int[] li_bucket1 = new int[scenario.start_p.length];
		int[] li_bucket2 = new int[scenario.start_p.length];
		double[] aa = new double[scenario.all_alloc];
		Metrics metrics = new Metrics();
		SimulateResult results = new SimulateResult(metrics, Double.NaN, Double.NaN, null, null);
		MapElement li_me = new MapElement(null, aa, results, null, null);

	        return lookup_interpolate_fast(p, period, false, false, li_dbucket, li_bucket1, li_bucket2, li_me);
        }

        private MapElement lookup_interpolate_fast(double[] p, int period, boolean fast_path, boolean generate, double[] li_dbucket, int[] li_bucket1, int[] li_bucket2, MapElement li_me)
        {
		MapElement me = li_me;
	        MapPeriod next_map = map[period];
		double[] bucket_f = scenario.pToFractionalBucket(p, li_dbucket);
		int[] below = li_bucket1;
		int[] above = li_bucket2;
		for (int i = 0; i < bucket_f.length; i++)
		{
		        below[i] = (int) Math.floor(bucket_f[i]);
			if (below[i] < next_map.bottom[i])
			{
			        above[i] = next_map.bottom[i];
				below[i] = next_map.bottom[i]; // + (generate ? 1 : 0); // Extrapolation problematic for annuities.
			}
			else if (below[i] + 1 > next_map.bottom[i] + next_map.length[i] - 1)
			{
			        above[i] = next_map.bottom[i] + next_map.length[i] - 1; // Don't extrapolate below zero.
				below[i] = next_map.bottom[i] + next_map.length[i] - 1;
			}
			else
			{
			        above[i] = below[i] + 1;
			}
		        if (generate && !config.generate_interpolate)
				// Use below for generation.
				// When we used to interpolate generation we ran into problems with variable withdrawals and zero public_assistance.
				// It would cause a minimum risk wedge for low portfolio sizes as -Infinity consume utilities back up.
				// Lack of interpolation results in a lot of map noise.
			        above[i] = below[i];
			else if (!generate && !config.validate_interpolate)
				// Use above for validation.
				// When we used to interpolate validation we ran into problems with variable withdrawals and zero public_assistance.
				// It would cause a few paths to go to zero at age 110 or so, resulting in a utility and path metric of -infinity.
				// We don't run into problems any more. Not sure why.
				// Can still run into problems in the presence of taxes.
			        below[i] = above[i];
			else if (bucket_f[i] - below[i] > 0.5)
			{
			        // Swap above and below so below now contains the closest bucket.
			        // This will allow us to get the most accurate extrapolations, since the distance we have to extrapolate over is less.
			        int tmp = below[i];
			        below[i] = above[i];
			        above[i] = tmp;
			}
		}
		MapElement map_below = next_map.get(below);
		double[] aa = me.aa;
	        double spend = map_below.spend;
	        double consume = map_below.consume;
		final boolean maintain_all = generate && !config.skip_dump_log && !config.conserve_ram;
		Metrics metrics = me.results.metrics;
		double metric = 0.0;
		if (!fast_path || generate)
		{
		        metric = map_below.metric_sm;
			if (maintain_all)
			{
				metrics = map_below.results.metrics.clone();
				me.results.metrics = metrics;
			}
		}
		if (!fast_path || !generate)
		{
		        System.arraycopy(map_below.aa, 0, aa, 0, aa.length);
		}
		for (int i = 0; i < bucket_f.length; i++)
		{
			if (above[i] != below[i])
			{
			        // Interpolate one dimension at a time.
			        int tmp = below[i];
			        below[i] = above[i];
				MapElement map_above = next_map.get(below);
			        below[i] = tmp;
			        //double weight = bucket_f[i] - below[i]; // Interpolate in bucket space.
				double weight = (p[i] - map_below.rps[i]) / (map_above.rps[i] - map_below.rps[i]); // Interpolate in p space. Extrapolate high values.
				if (!fast_path || generate)
				{
				        double weight_extrapolate = weight;
					// Interpolate, but avoid -Infinity * 0 = NaN and -Infinity + -Infinity * -ve = NaN.
					double delta = map_above.metric_sm;
					if (!Double.isInfinite(delta))
					    delta -= map_below.metric_sm;
					if (weight_extrapolate != 0 && !(Double.isInfinite(metric) && Double.isInfinite(delta)))
						metric += weight_extrapolate * delta;
					if (maintain_all)
					{
					        Metrics metrics_below = map_below.results.metrics;
						Metrics metrics_above = map_above.results.metrics;
						for (MetricsEnum m : MetricsEnum.values())
						{
							delta = metrics_above.get(m);
							if (!Double.isInfinite(delta))
								delta -= metrics_below.get(m);
							double met = metrics.get(m);
							if (weight_extrapolate != 0 && !(Double.isInfinite(met) && Double.isInfinite(delta)))
								metrics.set(m, met + weight_extrapolate * delta);
						}
					}
				}
				if (!fast_path || !generate)
			        {
					double weight_no_extrapolate = weight;
					weight_no_extrapolate = Math.max(weight_no_extrapolate, 0);
					weight_no_extrapolate = Math.min(weight_no_extrapolate, 1);
					for (int a = 0; a < scenario.all_alloc; a++)
						aa[a] += weight_no_extrapolate * (map_above.aa[a] - map_below.aa[a]);
					if (!fast_path)
					{
					        spend += weight_no_extrapolate * (map_above.spend - map_below.spend);
						consume += weight_no_extrapolate * (map_above.consume - map_below.consume);
					}
				}
			}
		}
		if (!fast_path || generate)
		{
			if (config.success_mode_enum == MetricsEnum.TW || config.success_mode_enum == MetricsEnum.NTW)
			{
				if (metric > 1)
					// Over extrapolated.
					metric = 1;
				if (metric <= 0)
					metric = 0;
			}
			me.metric_sm = metric;
			if (maintain_all)
			{
				for (MetricsEnum m : new MetricsEnum[] {MetricsEnum.TW, MetricsEnum.NTW})
				{
					double met = metrics.get(m);
					if (met > 1)
						met = 1;
					if (met <= 0)
						met = 0;
					metrics.set(m, met);
				}
			}
		}
		if (!fast_path || !generate)
		{
		        // Keep bounded and summed to one as exactly as possible.
		        double sum = 0;
		        for (int a = 0; a < scenario.all_alloc; a++)
			{
			        double alloc = aa[a];
			        if (alloc <= 0)
			                alloc = 0;
			        if (alloc > 1)
			                alloc = 1;
				aa[a] = alloc;
				if (a < config.normal_assets)
				        sum += alloc;
			}
			for (int a = 0; a < config.normal_assets; a++)
			      aa[a] /= sum;
			assert(spend >= 0);
			assert(consume >= 0);
			me.spend = spend;
			me.consume = consume;
		}
		me.rps = p;
		return me;
	}

	// Simulation core.
	@SuppressWarnings("unchecked")
        protected SimulateResult simulate(double[] initial_aa, double[] bucket_p, int period, Integer num_sequences, int num_paths_record, boolean generate, Returns returns)
	{
		boolean cost_basis_method_immediate = config.cost_basis_method.equals("immediate");
		boolean variable_withdrawals = !config.vw_strategy.equals("amount") && !config.vw_strategy.equals("retirement_amount");
		VitalStats vital_stats = scenario.vital_stats;
		AnnuityStats annuity_stats = scenario.annuity_stats;
		int total_periods = (int) (config.max_years * returns.time_periods);
		int max_periods = total_periods - period;

		int step_periods;
		if (generate)
			step_periods = 1;
		else
			step_periods = max_periods;

		int return_periods;
		if (generate)
		        return_periods = total_periods;
		else
		        return_periods = step_periods;

		int len_available;
		if (returns.wrap)
			len_available = returns.data.size();
		else
			len_available = returns.data.size() - step_periods + 1;

		int num_paths;
		if ((num_sequences != null) && (!generate || config.time_varying))
			num_paths = num_sequences;
		else
			num_paths = len_available;
		assert (num_paths >= 0);

		if (Arrays.asList("none", "once").contains(returns.ret_shuffle))
			assert(num_paths <= len_available);

		if (initial_aa == null)
		{
		        MapElement res = lookup_interpolate(bucket_p, period);
			initial_aa = res.aa;
		}

		double[] aa1 = new double[scenario.all_alloc]; // Avoid object creation in main loop; slow.
		double[] aa2 = new double[scenario.all_alloc];

		double[] li_p = new double[scenario.start_p.length];
	        double[] li_dbucket = new double[scenario.start_p.length];
		int[] li_bucket1 = new int[scenario.start_p.length];
		int[] li_bucket2 = new int[scenario.start_p.length];
		double[] li_aa = new double[scenario.all_alloc];
		Metrics li_metrics = new Metrics();
		SimulateResult li_results = new SimulateResult(li_metrics, Double.NaN, Double.NaN, null, null);
		MapElement li_me = new MapElement(null, li_aa, li_results, null, null);

		int len_returns = 0;
		double rcr_step = Math.pow(config.accumulation_ramp, 1.0 / returns.time_periods);
		double initial_rcr = config.rcr * Math.pow(rcr_step, period);
		double tw_goal = 0.0;
		double ntw_goal = 0.0;
		double consume_goal = 0.0;
		double combined_goal = 0.0;
		double inherit_goal = 0.0;
		double tax_goal = 0.0;
		double cost = 0.0;
		double consume_alive_discount = Double.NaN;
		Metrics metrics = new Metrics();
		List<List<PathElement>> paths = null;
		if (!generate)
		        paths = new ArrayList<List<PathElement>>();
		double[][] returns_array = null;
		double[] tax_annuity_credit_expire = new double[total_periods]; // Nominal amount.
		Tax tax = null;
	        if (generate)
		        tax = new TaxImmediate(scenario, config.tax_immediate_adjust);
		else
		        tax = Tax.taxFactory(scenario, config.cost_basis_method);
		double spend_annual = Double.NaN; // Amount spent on consumption and purchasing annuities.
		double consume_annual = Double.NaN;
		double consume_annual_key = Double.NaN;  // Cache provides 30% speedup for generate.
		double consume_annual_value = Double.NaN;
		//double consume_utility = 0.0;
		for (int s = 0; s < num_paths; s++)
		{
		        double p = (scenario.tp_index == null ? 0 : bucket_p[scenario.tp_index]);
			double ria = (scenario.ria_index == null ? 0 : bucket_p[scenario.ria_index]); // Payout is after tax amount.
			double nia = (scenario.nia_index == null ? 0 : bucket_p[scenario.nia_index]);
			double[] aa = aa1;
			double[] free_aa = aa2;
			System.arraycopy(initial_aa, 0, aa, 0, scenario.all_alloc);
			boolean retired = false;
			double spend_retirement = Double.NaN;
			double cpi = 1;
			if (scenario.do_tax)
			{
			        if (config.tax_rate_annuity != 0)
				        Arrays.fill(tax_annuity_credit_expire, 0);
			        tax.initial(p, aa);
			}
			double returns_probability = 0.0;
			double p_post_inc_neg = p;
			double solvent_always = 1;
			double tw_goal_path = 0.0;
			double ntw_goal_path = 0.0;
			double consume_goal_path = 0.0;
			double combined_goal_path = 0.0;
			double inherit_goal_path = 0.0;
			double tax_goal_path = 0.0;
			double cost_path = 0.0;
			List<PathElement> path = null;
			if (s < num_paths_record)
			        path = new ArrayList<PathElement>();
			int index;
			if (returns.ret_shuffle.equals("all") && (!generate || config.time_varying))
			{
				if (!generate || returns.reshuffle)
					returns_array = returns.shuffle_returns(return_periods);
				else
				        returns_array = returns.shuffle_returns_cached(s, return_periods);
				len_returns = return_periods;
				if (generate)
				        index = period;
				else
				        index = 0;
				returns_probability = 1.0 / num_paths;
			}
			else
			{
				returns_array = returns.returns_unshuffled;
				len_returns = returns_array.length;
				index = s % len_returns;
				returns_probability = returns.returns_unshuffled_probability[index];

			}
			MapElement me = null;
			double rcr = initial_rcr;
			double raw_alive = Double.NaN;
			double alive = Double.NaN;
			int y = 0;
			while (y < step_periods)
			{
				double raw_dying = vital_stats.raw_dying[period + y];
				raw_alive = vital_stats.raw_alive[period + y + 1];
			        double prev_alive = vital_stats.alive[period + y];
				alive = vital_stats.alive[period + y + 1];
				double dying = vital_stats.dying[period + y];
				double avg_alive = (alive + prev_alive) / 2.0;

		                double p_prev_inc_neg = p;
				double p_prev_exc_neg = p;
				if (p_prev_exc_neg < 0)
				        p_prev_exc_neg = 0;
			        double ria_prev = ria;
				double nia_prev = nia;

				double amount_annual; // Contribution/withdrawal amount.
				boolean retire = period + y >= (config.retirement_age - config.start_age) * returns.time_periods;
				if (config.cw_schedule != null)
				{
					spend_annual = config.withdrawal;
					amount_annual = config.cw_schedule[period + y] * returns.time_periods;
				}
				else
				{
				        double income = ria + nia;
					if (retire)
					{
					        income += config.defined_benefit;
					        if (variable_withdrawals)
						{
						        // Full investment portfolio amount subject to contrib choice.
						        // Not so for pre-retirement, only amount beyond RCR.
						        spend_annual = p + income;
						}
						else
						{
						        if (!retired)
							{
							        spend_retirement = config.vw_strategy.equals("amount") ? config.withdrawal : income + config.vw_percentage * p;
								retired = true;
							}
						        spend_annual = spend_retirement;
						}
						amount_annual = income - spend_annual;
					}
					else
					{
					        spend_annual = config.withdrawal;
						amount_annual = rcr + income;
						rcr *= rcr_step;
					}
				}

				consume_annual = spend_annual;
				double first_payout = 0;
				double real_annuitize = 0;
				if (scenario.ria_index != null)
				{
					real_annuitize = consume_annual * aa[scenario.ria_aa_index];
					consume_annual -= real_annuitize;
					double ria_purchase = real_annuitize * (1 - config.tax_rate_annuity) / annuity_stats.real_annuity_price[period + y];
					ria += ria_purchase;
					if (config.annuity_payout_immediate)
					        first_payout += ria_purchase;
				}
				double nominal_annuitize = 0;
				if (scenario.nia_index != null)
				{
					nominal_annuitize = consume_annual * aa[scenario.nia_aa_index];
					consume_annual -= nominal_annuitize;
					double nia_purchase = nominal_annuitize * (1 - config.tax_rate_annuity) / annuity_stats.nominal_annuity_price[period + y];
					nia += nia_purchase;
					if (config.annuity_payout_immediate)
					        first_payout += nia_purchase;
				}
				if ((scenario.ria_index != null || scenario.nia_index != null) && config.tax_rate_annuity != 0 &&
				        (!generate || (!config.tax_annuity_us && config.tax_annuity_canadian_nominal_generate_credit)))
				{
				        double nia_tax_credit = (real_annuitize + nominal_annuitize) * config.tax_rate_annuity / annuity_stats.annuity_le[period + y];
					nia += nia_tax_credit;
					if (config.annuity_payout_immediate)
					        first_payout += nia_tax_credit;
					if (config.tax_annuity_us)
					{
						int expire = period + y + annuity_stats.annuity_le[period + y] - (config.annuity_payout_immediate ? 1 : 0);
						if (expire < tax_annuity_credit_expire.length)
							tax_annuity_credit_expire[expire] += cpi * nia_tax_credit;
						nia -= tax_annuity_credit_expire[period + y] / cpi;
					}
				}
				if (retire && variable_withdrawals)
				{
					consume_annual += first_payout;
				        double not_consumed;
					if (config.annuity_partial || (ria == 0 && nia == 0))
					        not_consumed = consume_annual * (1 - aa[scenario.spend_fract_index]);
					else if (ria_prev == 0 && nia_prev == 0)
					        // Allow extra year to complete initial annuitization.
					        // This prevents a bump in consumption from having to consume full first_payout,
					        // and not being able to re-annuitize a small part of it.
					        not_consumed = first_payout * (1 - aa[scenario.spend_fract_index]);
					else
					        not_consumed = 0;
					consume_annual -= not_consumed;
					amount_annual += not_consumed;
			        }
				else
				        amount_annual += first_payout;
				double target_consume_annual = consume_annual;

				p += amount_annual / returns.time_periods;

				double p_preinvest = p;
				if (p >= 0)
				{
					// Invest.
					double tot_return = 0.0;
					for (int i = 0; i < config.normal_assets; i++)
						tot_return += aa[i] * (1 + returns_array[index][i]);
					p *= tot_return;
				}
				else
				{
					p *= (1.0 + config.ret_borrow);
				}
				if (scenario.ria_index != null || scenario.nia_index != null)
				{
					double cpi_delta = returns_array[index][scenario.cpi_index];
					cpi *= 1 + cpi_delta;
					ria /= 1 + cpi_delta * config.tax_rate_annuity;
					nia /= 1 + cpi_delta;
				}
				p_post_inc_neg = p;
				if (!config.negative_p && p < 0.0)
				{
				        consume_annual += p * returns.time_periods;
					// We used to truncate negative consume_annual values, but this is problematic.
					// It caused donate above to sometimes occur for rps config.withdrawal for period 0.
					// Truncation would cause different buckets at and just above rps 0 to have the same consume and combined metric.
					// Then in the prior year just above withdrawal we would access these sub-buckets causing a comparison in which
					// submetrics would make the low contrib values preferable, but it would then fail to do so.
					// A non-zero ret_borrow could trigger this to occur. If this is desired some other solution will then be needed.
					if (-1e-12 * config.withdrawal < consume_annual && consume_annual < 0)
					        consume_annual = 0; // Rounding error.
				}
				if (consume_annual < 0 && p_prev_inc_neg < 0)
				        consume_annual = 0;
				assert(consume_annual >= 0);
				if (!config.negative_p && p < 0.0)
				{
				        p = 0;
				}

				// For consistency between single-step and multi-step results could do the following.
				// But inconsitency is good, it indicates pf_guaranteed should be increased.
				// if p > self.pf_guaranteed:
				//   p = self.pf_guaranteed

				int new_period = period + y + 1;
				boolean tax_time = scenario.do_tax && (returns.time_periods < 1 || new_period % Math.round(returns.time_periods) == 0);
				double total_tax_pending = 0;
				double tax_amount = 0;
				if (scenario.do_tax)
				{
					// Tax depends on our new asset allocation which depends on our portfolio size which depends on how much tax we pay.
					// We perform a first order estimate.
					total_tax_pending = tax.total_pending(p, p_preinvest, aa, returns_array[index]);
					// It may be worth performing a second order estimate when generating.
					// Empirically though this hasn't been found to make any difference.
					//
					// if (!generate)
					// {
					//         res = lookup_bucket(null, p - total_tax_pending, new_period, generate, returns);
					//         total_tax_pending = tax.total_pending(p, p_preinvest, res.aa, returns_array[index]);
					// }
				}

				// Get aa recommendation.
				double[] aa_prev = aa;
				if (y + 1 < max_periods)
				{
				        int fperiod;
					if (returns.time_periods == config.generate_time_periods)
					        fperiod = new_period;
					else
					        fperiod = (int) (new_period * config.generate_time_periods / returns.time_periods);
					if (scenario.tp_index != null)
					        li_p[scenario.tp_index] = p - total_tax_pending;
					if (scenario.ria_index != null)
					        li_p[scenario.ria_index] = ria;
					if (scenario.nia_index != null)
					        li_p[scenario.nia_index] = nia;

					li_me.aa = free_aa;
					me = lookup_interpolate_fast(li_p, fperiod, true, generate, li_dbucket, li_bucket1, li_bucket2, li_me);

					if (!generate)
					{
						// Rebalance.
						boolean rebalance_period = new_period % Math.round(returns.time_periods / config.rebalance_time_periods) == 0;
						if (!rebalance_period || config.rebalance_band_hw > 0)
						{
						        double[] new_aa = aa.clone(); // Tangency not well defined.
							double new_aa_sum = 0.0;
							for (int a = 0; a < config.normal_assets; a++)
							{
							        double alloc = aa[a] * (1 + returns_array[index][a]);
							        new_aa[a] = alloc;
								new_aa_sum += alloc;
							}
						        for (int a = 0; a < config.normal_assets; a++)
							        new_aa[a] /= new_aa_sum;
							aa = new_aa;
							if (rebalance_period)
							{
							        for (int a = 0; a < config.normal_assets; a++)
								{
									if (Math.abs(aa[a] - me.aa[a]) >= config.rebalance_band_hw)
									{
										aa = me.aa;
										break;
									}
								}
							}
						}
					        else
						{
						        aa = me.aa;
						}
						if (tax_time)
						{
						        tax_amount = tax.tax(p, p_preinvest, aa, returns_array[index]);
							if (cost_basis_method_immediate)
							        tax_amount = total_tax_pending; // Ensure generated and simulated metrics match for immediate.
						        p -= tax_amount;
							if (!config.negative_p && p < 0.0)
							{
								p = 0;
							}
						}
					}
				}
				else
				{
					aa = null;
				}

				// Record solvency.
				// Consumption might have been reduced to prevent p falling below 0.  Interpolate based on where it would have fallen.
				double solvent;
				if (config.success_mode_enum == MetricsEnum.TW || config.success_mode_enum == MetricsEnum.NTW)
				{
				        assert(config.floor == 0);
					// Get artifacts if not smooth. AA plot contains horizontal lines at low RPS in retirement.
					if (p_post_inc_neg >= 0 && p_prev_inc_neg >= 0)
					{
						solvent = 1.0;
					}
					else if (p_prev_inc_neg >= 0)
					{
						// Interpolate solvency in year of bankruptcy.
						solvent = p_prev_inc_neg / (p_prev_inc_neg - p_post_inc_neg);
					}
					else if (p_post_inc_neg >= 0)
					{
						// Contribution brought us out of insolvency.
						solvent = p_post_inc_neg / (p_post_inc_neg - p_prev_inc_neg);
					}
					else
					{
						solvent = 0.0;
					}
			        }
				else
				{
				        solvent = (consume_annual > config.floor ? 1 : 0); // No easy way to smooth.
				}
				if (solvent < 1)
				        solvent_always = 0;
				// Interpolate when we can to ensure a smooth transition rate in metrics (across periods? over aa/contrib search space?)
				// as consume_annual falls below target_consume_annual.
				// Without this we get horizontal lines for low RPS asset allocations with fixed withdrawals.
				double consume_path_utility = 0.0;
				double path_consume = consume_annual;
				if (target_consume_annual != config.defined_benefit)
				{
				        double consume_solvent = (consume_annual - config.defined_benefit) / (target_consume_annual - config.defined_benefit);
				        // We somewhat arbitrarily set the zero consumption level to defined_benefit and interpolate based on the distance from that.
					// This is better than using 0, and then computing utility(0) on a power utility function.
					if (consume_solvent != 0.0)
					{
						 double utility;
						if (target_consume_annual == consume_annual_key)
							utility = consume_annual_value;
						else
						{
							consume_annual_key = target_consume_annual;
							consume_annual_value = scenario.utility_consume_time.utility(consume_annual_key);
							utility = consume_annual_value;
						}
						consume_path_utility += consume_solvent * utility;
					}
					if (consume_solvent != 1.0)
					{
						consume_path_utility += (1.0 - consume_solvent) * scenario.utility_consume_time.utility(config.defined_benefit);
						path_consume = scenario.utility_consume_time.inverse_utility(consume_path_utility);
						        // So consume and jpmorgan metrics match when gamma = 1/psi.
					}
				}
				else
				        consume_path_utility = scenario.utility_consume_time.utility(consume_annual);
				boolean compute_utility = !config.utility_retire || retire;
				if (compute_utility)
				        consume_alive_discount = avg_alive;
				else
				        consume_alive_discount = 0;
				double consume_alive_utility = consume_alive_discount * consume_path_utility / returns.time_periods;
				consume_goal_path += consume_alive_utility;
				combined_goal_path += consume_alive_utility;
				if (config.utility_dead_limit != 0.0 && compute_utility)
				{
					// We ignore any taxes that may be pending at death.
				        double inherit_utility = scenario.utility_inherit.utility(p_prev_exc_neg);
					// We now use p_prev_inc_amount in place of inherit_p in the utility function above.
					// Effectively death occurs at the start of the cycle.
					// donate_above makes an estimate of where donation utility exceeds aggregate utility that would
					// otherwise be experienced. Unfortunately, in the presence of high death probabilities, the aggregate
					// utility might do best just below this point on account of the additional utility from the use of inherit_p.
					// As consumption increases, inherit utility falls, but by too much because inherit_p is typically larger
					// than p_prev_inc_amount. For higher initial p utility is reduced more.
					// This reduces the true location of donate above, and so the calculated donate above is irrelevant.
					// A lower non-donating consumption will be selected.
					// In practical terms, when we used inherit_p, we found no donations occuring for ages 100-119.
					inherit_goal_path += dying * inherit_utility;

					// Feel like we should be able to do:
					//     double combined_goal_path += inherit_goal;
					// but can't since utility is not additive/power and exponential utility have a maximum asymptote which we could exceed.
					// Instead we pro-rate the distance to the asymptote.
					double inherit_proportion = (inherit_utility - scenario.utility_inherit.u_0) / (scenario.utility_inherit.u_inf - scenario.utility_inherit.u_0);
					double combined_inherit_utility;
					if (consume_path_utility == Double.NEGATIVE_INFINITY)
					        combined_inherit_utility = 0;
					else
					        combined_inherit_utility = inherit_proportion * (scenario.utility_consume_time.u_inf - consume_path_utility);
					assert(consume_path_utility + combined_inherit_utility <= scenario.utility_consume_time.u_inf);
					combined_goal_path += consume_alive_discount * config.utility_dead_limit * combined_inherit_utility / returns.time_periods;
					        // Multiply by consume_alive_discount not dying because well-being is derived from being able to bequest,
					        // not the actual bequest.
				}
				tax_goal_path += consume_alive_discount * tax_amount * returns.time_periods;
				if (config.success_mode_enum == MetricsEnum.COST)
				{
				        // Expensive.
					cost_path += amount_annual / returns.time_periods * Math.pow(1.0 + config.ret_borrow, - (period + y) / returns.time_periods);
				}
				tw_goal_path += avg_alive * solvent;
				ntw_goal_path += raw_dying * solvent_always;

				// Record path.
				if (s < num_paths_record)
				{
				        path.add(new PathElement(aa_prev, p_prev_inc_neg, path_consume, ria_prev, nia_prev, real_annuitize, nominal_annuitize, tax_amount));
				}
				free_aa = aa_prev;

				// Next iteration.
				y += 1;
				index = (index + 1) % len_returns;
			}
			// Record solvency.
			if (!generate || period == total_periods - 1)
			{
			        ntw_goal_path += raw_alive * solvent_always;
			}
			// Add individual path metrics to overall metrics.
			tw_goal += tw_goal_path * returns_probability;
			ntw_goal += ntw_goal_path * returns_probability;
			consume_goal += consume_goal_path * returns_probability;
			combined_goal += combined_goal_path * returns_probability;
			inherit_goal += inherit_goal_path * returns_probability;
			tax_goal += tax_goal_path * returns_probability;
			cost += cost_path * returns_probability;
			// The following code is performance critical.
		        if (generate && max_periods > 1)
			{
				final boolean maintain_all = generate && !config.skip_dump_log && !config.conserve_ram;
				if (maintain_all)
				{
					for (MetricsEnum m : MetricsEnum.values())
					{
						metrics.set(m, metrics.get(m) + me.results.metrics.get(m) * returns_probability);
					}
				}
				else
				{
				        // Get and set are slow; access fields directly.
					metrics.metrics[config.success_mode_enum.ordinal()] += me.metric_sm * returns_probability;
					// Other metric values invalid.
				}
			}
			// Record path.
			if (s < num_paths_record)
			{
				// 8% speedup by not recording path if know it is not needed
			    path.add(new PathElement(null, p, Double.NaN, ria, nia, Double.NaN, Double.NaN, Double.NaN));
				        // Ignore any pending taxes associated with p, mainly because they are difficult to compute.
				paths.add(path);
			}
		}

		if (generate && max_periods > 1)
		{
		        tw_goal += metrics.get(MetricsEnum.TW) * vital_stats.sum_avg_alive[period + 1];
			ntw_goal += metrics.get(MetricsEnum.NTW) * vital_stats.raw_alive[period + 1];
			if (config.utility_epstein_zin)
			{
			        double divisor = vital_stats.consume_divisor_period[period];
				double future_utility_risk = metrics.get(MetricsEnum.COMBINED);
			        double future_utility_time = scenario.utility_consume_time.utility(scenario.utility_consume.inverse_utility(future_utility_risk));
				combined_goal = (combined_goal + (divisor - consume_alive_discount) * future_utility_time) / divisor;
			}
			else
			        combined_goal += metrics.get(MetricsEnum.COMBINED);
			cost += metrics.get(MetricsEnum.COST);
		}

		if (config.vw_strategy.equals("retirement_amount") && generate)
		{
		        // This is a run time strategy. The withdrawal amount will vary depending on the run not the map, and so generated metrics are invalid.
		        consume_goal = 0;
		        inherit_goal = 0;
		        combined_goal = 0;
		        tax_goal = 0;
		}
		else if (config.utility_epstein_zin)
		{
		        if (generate)
			        combined_goal = scenario.utility_consume.utility(scenario.utility_consume_time.inverse_utility(combined_goal));
			else
			        combined_goal = 0; // Epstein-Zin utility can't be estimated by simulating paths.
		}

		// For reporting and success map display purposes keep goals normalized across ages.
		tw_goal /= vital_stats.sum_avg_alive[period];
		ntw_goal /= vital_stats.raw_alive[period];

		if (1.0 < tw_goal && tw_goal <= 1.0 + 1e-6)
			tw_goal = 1.0;
		if (1.0 < ntw_goal && ntw_goal <= 1.0 + 1e-6)
			ntw_goal = 1.0;
		assert (0.0 <= tw_goal && tw_goal <= 1.0);
		assert (0.0 <= ntw_goal && ntw_goal <= 1.0);

		Metrics result_metrics = new Metrics(tw_goal, ntw_goal, consume_goal, inherit_goal, combined_goal, tax_goal, cost);

		String metrics_str = null;  // Useful for debugging.
		if (!config.skip_dump_log)
		{
		        StringBuilder sb = new StringBuilder();
                        sb.append("{'CONSUME': ");   sb.append(result_metrics.get(MetricsEnum.CONSUME));
                        sb.append(", 'INHERIT': ");    sb.append(result_metrics.get(MetricsEnum.INHERIT));
                        sb.append(", 'SUBMETRICS': "); sb.append(metrics.get(MetricsEnum.COMBINED));
                        //sb.append(", 'SUBCONSUME': "); sb.append(metrics.get(MetricsEnum.CONSUME));
                        //sb.append(", 'SUBINHERIT': "); sb.append(metrics.get(MetricsEnum.INHERIT));
			sb.append("}");
			metrics_str = sb.toString();
		}

		return new SimulateResult(result_metrics, spend_annual, consume_annual, paths, metrics_str);
	}

	// Validation.
        private SimulateResult simulate_paths(int period, Integer num_sequences, int num_paths_record, double[] p, Returns returns)
	{
	        SimulateResult res = simulate(null, p, period, num_sequences, num_paths_record, false, returns);
		return res;
	}

	public Metrics[] simulate_retirement_number(final Returns returns) throws ExecutionException
	{
	        final int period = (int) Math.round((config.retirement_age - config.start_age) * returns.time_periods);
		final int bucket_0 = 0;
		final int bucket_1 = config.retirement_number_steps + 1;

		final Metrics[] metrics = new Metrics[bucket_1 - bucket_0];

		final int bucketsPerTask = ((int) Math.ceil((bucket_1 - bucket_0) / (double) config.tasks_validate));
		List<Callable<Integer>> tasks = new ArrayList<Callable<Integer>>();
		for (int block_bucket_1 = bucket_1; block_bucket_1 > bucket_0; block_bucket_1 -= bucketsPerTask)
		{
			final int b1 = block_bucket_1;
			tasks.add(new Callable<Integer>()
			{
				public Integer call()
				{
					Thread.currentThread().setPriority((Thread.MIN_PRIORITY + Thread.NORM_PRIORITY) / 2);
					int b0 = Math.max(bucket_0, b1 - bucketsPerTask);
					for (int bucket = b0; bucket < b1; bucket++)
					{
						Returns local_returns = returns.clone();
					        double tp = bucket * config.pf_retirement_number / config.retirement_number_steps;
						double[] p = scenario.start_p.clone();
						p[scenario.tp_index] = tp;
						SimulateResult res = simulate_paths(period, config.num_sequences_retirement_number, 0, p, local_returns);
						metrics[bucket] = res.metrics;
					}
					return null;
				}
			});
		}

		invoke_all(tasks);

		return metrics;
	}

	// // The returns contain autocorrelations and so the success metrics for those
	// // need computing.
	// public Metrics[][] simulate_success_lines(final Returns returns) throws ExecutionException
	// {
	// 	List<Callable<Integer>> tasks = new ArrayList<Callable<Integer>>();

	// 	final int period_0 = 0;
	// 	final int period_1 = (int) (config.max_years * returns.time_periods);
	// 	final int bucket_0 = 0;
	// 	final int bucket_1 = (int) Math.floor(config.pf_validate / config.success_lines_scale_size);

	// 	final Metrics[][] metrics = new Metrics[period_1 - period_0][bucket_1 - bucket_0 + 1];

	// 	final int bucketsPerTask = ((int) Math.ceil((bucket_1 - bucket_0 + 1) / (double) config.tasks));
	// 	for (int block_bucket_0 = bucket_0; block_bucket_0 < bucket_1 + 1; block_bucket_0 += bucketsPerTask)
	// 	{
	// 		final int b0 = block_bucket_0;
	// 		tasks.add(new Callable<Integer>()
	// 		{
	// 			public Integer call()
	// 			{
	// 				Thread.currentThread().setPriority((Thread.MIN_PRIORITY + Thread.NORM_PRIORITY) / 2);
	// 				Returns local_returns = returns.clone();
	// 				for (int period = period_0; period < period_1; period++)
	// 			        {
	// 					int b1 = Math.min(bucket_1 + 1, b0 + bucketsPerTask);
	// 					for (int bucket = b0; bucket < b1; bucket++)
	// 					{
	// 					        double pf = bucket * config.success_lines_scale_size;
	// 						SimulateResult res = simulate_paths(period, config.num_sequences_success, 0, pf, local_returns);
	// 						metrics[period][bucket] = res.metrics;
	// 					}
	// 				}
	// 				return null;
	// 			}
	// 		});

	// 	}

	// 	invoke_all(tasks);

	// 	return metrics;
	// }

        public double jpmorgan_metric(int age, List<List<PathElement>> paths, int num_batches, Returns returns)
        {
	        assert(config.max_jpmorgan_paths % num_batches == 0);
		int first_age = age;
		int period_offset = (int) Math.round((age - config.start_age) * returns.time_periods);
	        if (config.utility_retire && age < config.retirement_age)
		        first_age = config.retirement_age;
	        int first_period = (int) Math.round((first_age - config.validate_age) * returns.time_periods);
		double u = 0;
	        for (int period = first_period; period < (int) ((config.max_years - (config.validate_age - config.start_age)) * returns.time_periods); period++)
		{
		        double u2 = 0;
			int num_paths = config.max_jpmorgan_paths / num_batches;
			for (int pi = 0; pi < num_paths; pi++)
			{
			        List<PathElement> path = paths.get(pi);
			        PathElement e = path.get(period);
				double u_consume = scenario.utility_consume_time.utility(e.consume_annual);
				double u_inherit = scenario.utility_inherit.utility((e.p > 0) ? e.p : 0);
				double u_combined = u_consume;
				if (config.utility_dead_limit != 0)
				{
				        double inherit_proportion = (u_inherit - scenario.utility_inherit.u_0) / (scenario.utility_inherit.u_inf - scenario.utility_inherit.u_0);
				        u_combined += config.utility_dead_limit * inherit_proportion * (scenario.utility_consume_time.u_inf - u_consume);
				}
				u2 += scenario.utility_consume.utility(scenario.utility_consume_time.inverse_utility(u_combined));
			}
			u2 /= num_paths;
			double weight = scenario.vital_stats.metric_weight(MetricsEnum.CONSUME, period_offset + period);
			u += weight * scenario.utility_consume_time.utility(scenario.utility_consume.inverse_utility(u2));
		}
		u /= scenario.vital_stats.metric_divisor(MetricsEnum.CONSUME, first_age);

		return u;
	}


	public PathMetricsResult path_metrics(final int age, final double[] p, Integer num_sequences, final int seed, final Returns returns) throws ExecutionException
	{
	        double max_paths = Math.max(config.max_distrib_paths, Math.max(config.max_pct_paths, Math.max(config.max_delta_paths, config.max_display_paths)));
		if (!config.skip_metric_jpmorgan)
		        max_paths = Math.max(max_paths, config.max_jpmorgan_paths);

		// Compute paths in batches so that we can calculate a sample standard deviation of the mean.
		Integer batch_size;
		int num_batches;
		if (num_sequences == null || num_sequences == 1)
		{
			batch_size = num_sequences;
			num_batches = 1;
		}
		else
		{
			assert (num_sequences % config.path_metrics_bucket_size == 0);
			batch_size = config.path_metrics_bucket_size;
			num_batches = num_sequences / batch_size;
		}
		final int num_paths_record = (int) Math.ceil((double) max_paths / num_batches);

		final SimulateResult[] results = new SimulateResult[num_batches];
		final List<List<PathElement>> paths = new ArrayList<List<PathElement>>();

	        List<Callable<Integer>> tasks = new ArrayList<Callable<Integer>>();
		final int batchesPerTask = ((int) Math.ceil(num_batches / (double) config.tasks_validate));
		final Integer fbatch_size = batch_size;
		final int fnum_batches = num_batches;
		for (int i0 = 0; i0 < num_batches; i0 += batchesPerTask)
		{
			final int fi0 = i0;
			tasks.add(new Callable<Integer>()
			{
				public Integer call()
				{
					Thread.currentThread().setPriority((Thread.MIN_PRIORITY + Thread.NORM_PRIORITY) / 2);
					int i1 = Math.min(fnum_batches, fi0 + batchesPerTask);
					for (int i = fi0; i < i1; i++)
					{
						Returns local_returns = returns.clone();
						local_returns.setSeed(seed + i);
						results[i] = simulate_paths((int) Math.round((age - config.start_age) * returns.time_periods), fbatch_size, num_paths_record, p, local_returns);
						if (!config.skip_metric_jpmorgan)
						        results[i].metrics.set(MetricsEnum.JPMORGAN, jpmorgan_metric(age, results[i].paths, fnum_batches, returns));
					}
					return null;
				}
			});
		}

		invoke_all(tasks);

		Metrics means = new Metrics();
		Metrics standard_deviations = new Metrics();
		for (MetricsEnum metric : MetricsEnum.values())
		{
			List<Double> samples = new ArrayList<Double>();
			for (int i = 0; i < results.length; i++)
			{
			        samples.add(results[i].metrics.get(metric));
				for (List<PathElement> pl : results[i].paths)
				        if (paths.size() < max_paths)
					        paths.add(pl);
					else
					        break;
			}
			means.set(metric, Utils.mean(samples));
		        standard_deviations.set(metric, Utils.standard_deviation(samples) / Math.sqrt(num_batches));
			        // Standard deviation of the sample mean is proportional to 1 sqrt(number of samples).
		}
		return new PathMetricsResult(scenario, means, standard_deviations, paths);
	}

        public TargetResult rps_target(int age, double target, Returns returns_target, boolean under_estimate) throws ExecutionException
	{
		double high = config.pf_guaranteed;
		double low = 0.0;
		double high_target = Double.NaN;
		double low_target = Double.NaN;
		double target_mean = Double.NaN;
		boolean first_time = true;
		while (high - low > 0.01 * config.withdrawal)
		{
			double mid = (high + low) / 2;
			double p_mid[] = scenario.start_p.clone();
			p_mid[scenario.tp_index] = mid;
			PathMetricsResult r = path_metrics(age, p_mid, config.num_sequences_target, 0, returns_target);
			target_mean = r.means.get(config.success_mode_enum);
			if (target_mean == target && first_time)
			{
			        // Trying to target 100% success.
			        high = Double.NaN;
				break;
			}
			first_time = false;
			if (target_mean < target)
			{
				low = mid;
			        low_target = target_mean;
			}
			else
			{
				high = mid;
				high_target = target_mean;
			}
		}
		if (under_estimate)
		        return new TargetResult(this, low, low_target);
		else
		        return new TargetResult(this, high, high_target);
	}

    public TargetResult rcr_target(int age, double target, boolean baseline, Returns returns_generate, Returns returns_target, boolean under_estimate) throws ExecutionException, IOException
	{
	        double keep_rcr = config.rcr;
		double high = config.withdrawal;
		double low = 0.0;
		AAMap map_loaded = null;
		double high_target = Double.NaN;
		double low_target = Double.NaN;
		double target_mean = Double.NaN;
		boolean first_time = true;
		while (high - low > 0.00005 * config.withdrawal)
		{
			double mid = (high + low) / 2;
			config.rcr = mid;
			if (baseline) {
			        map_loaded = this;
			} else {
			        AAMapGenerate map_precise = new AAMapGenerate(scenario, returns_generate);
			        map_loaded = new AAMapDumpLoad(scenario, map_precise);
			}
			PathMetricsResult r = map_loaded.path_metrics(age, scenario.start_p, config.num_sequences_target, 0, returns_target);
			target_mean = r.means.get(config.success_mode_enum);
			if (target_mean == target && first_time)
			{
			        high = Double.NaN;
				break;
			}
			if (target_mean < target)
			{
				low = mid;
			        low_target = target_mean;
			}
			else
			{
				high = mid;
				high_target = target_mean;
			}
		}
		config.rcr = keep_rcr;
		if (under_estimate)
		        return new TargetResult(map_loaded, low, low_target);
		else
		        return new TargetResult(map_loaded, high, high_target);
	}

        public void invoke_all(List<Callable<Integer>> tasks) throws ExecutionException
	{
		try
		{
			List<Future<Integer>> future_tasks = scenario.executor.invokeAll(tasks); // Will block until all tasks are finished
			// If a task dies due to an assertion error, it can't be caught within the task, so we probe for it here.
			for (Future<Integer> f : future_tasks)
			        //try
				//{
				//	f.get();
				//} catch (Exception e) {
				//	e.printStackTrace();
				//	System.exit(1);
				//}
			        f.get();
		}
		catch (InterruptedException e)
		{
			System.exit(1);
		}
        }

        // Human readable dump of generated data.
	public void dump_log()
	{
		for (int pi = 0; pi < map.length; pi++)
		{
		        System.out.printf("age %.2f:\n", (pi + config.start_age * config.generate_time_periods) / config.generate_time_periods);
			for (MapElement me : map[pi])
			        System.out.println(me);
		}
	}

        private double[] target_date_stocks = new double[] { 0.316, 0.422, 0.519, 0.609, 0.684, 0.754, 0.803, 0.844, 0.877, 0.903 };
                // S&P Target Date indexes as reported by iShares prospectus of 2012-12-01 for holdings as of 2012-06-30.
        private double target_date_offset = -5 + -2.5;

        protected double[] generate_aa(String aa_strategy, double age, double[] p)
        {
		double bonds;
		if (aa_strategy.equals("fixed"))
		        bonds = 1 - config.aa_fixed_stocks;
		else if (aa_strategy.equals("age_in_bonds"))
			bonds = age / 100.0;
		else if (aa_strategy.equals("age_minus_10_in_bonds"))
			bonds = (age - 10) / 100.0;
		else if (aa_strategy.equals("target_date"))
		{
		        double ytr = config.retirement_age - age;
			double findex = (ytr - target_date_offset) / 5;
			int index = (int) Math.floor(findex);
			double stocks;
			if (index < 0)
			        stocks = target_date_stocks[0];
			else if (index + 1 >= target_date_stocks.length)
			        stocks = target_date_stocks[target_date_stocks.length - 1];
			else
			        stocks = target_date_stocks[index] * (1 - (findex - index)) + target_date_stocks[index + 1] * (findex - index);
			bonds = 1 - stocks;
		}
		else
		{
		        assert(false);
			bonds = Double.NaN;
		}

		bonds = Math.min(1, bonds);

		if (config.defined_benefit != 0 && config.db_bond || config.savings_bond)
		{
		        int period = (int) Math.round((age - config.start_age) * config.generate_time_periods);
			int retire_period = (int) Math.round((Math.max(age, config.retirement_age) - config.start_age) * config.generate_time_periods);
			double future_income = 0;
		        for (int pp = period; pp < scenario.vital_stats.dying.length; pp++)
			{
			        double income = 0;
				double avg_alive = (scenario.vital_stats.raw_alive[pp] + scenario.vital_stats.raw_alive[pp + 1]) / 2;
			        if (pp < Math.round((config.retirement_age - config.start_age) * config.generate_time_periods))
				{
				        if (config.savings_bond)
					{
						if (pp == period)
					                continue;
						avg_alive /= scenario.vital_stats.raw_alive[period];
					        income = config.rcr * Math.pow(config.accumulation_ramp, pp / config.generate_time_periods);
					}
				}
				else
				{
				        if (config.db_bond)
					{
						if (config.vbond_discounted)
						{
						        avg_alive /= scenario.vital_stats.raw_alive[period];
						        if (pp == period)
							        continue;
					        }
						else
						        // If not discounting include pp=period and approximate chance of being alive at retirement_age as 1
						        // so that rule of thumb can lookup le from a table.
						        avg_alive /= scenario.vital_stats.raw_alive[retire_period];
					        income = config.defined_benefit;
					}
				}
				income *= avg_alive;
				if (config.vbond_discounted)
				        income /= Math.pow((1 + config.vbond_discount_rate), (pp - period) / config.generate_time_periods);
				future_income += income;
			}
			if (p[scenario.tp_index] > 0)
			    bonds -= (1 - bonds) * future_income / p[scenario.tp_index];
			                // db_bonds * (p + inc) = std_bonds * (p + inc).
			                // db_bonds = (std_bonds * p + inc) / (p + inc).
			                // db_bonds * (p + inc) - inc = std_bonds * p.
                                        // db_bonds * (1 + inc / p) - inc / p = std_bonds.
			                // std_bonds = db_bonds - (1 - db_bonds) * inc / p.
			else if (bonds < 1)
			        bonds = 0;
		}

		double max_stocks = scenario.max_stocks();
		bonds = Math.max(1 - max_stocks, bonds);

		double[] aa = new double[scenario.all_alloc];
		aa[config.asset_classes.indexOf("stocks")] = 1 - bonds;
		aa[config.asset_classes.indexOf("bonds")] = bonds;

		return aa;
	}

        public static AAMap factory(BaseScenario scenario, String aa_strategy, Returns returns) throws IOException, ExecutionException
        {
	        Config config = scenario.config;

	        if (aa_strategy.equals("file"))
		        return new AAMapDumpLoad(scenario, config.validate);
		else if (returns != null)
		        return new AAMapGenerate(scenario, returns);
		else
		        // returns == null. Hack. Called by targeting. Should get AAMapGenerate to handle. Then delete AAMapStatic.java.
		        return new AAMapStatic(scenario, aa_strategy);
	}

        public AAMap(BaseScenario scenario)
        {
	        this.scenario = scenario;
	        this.config = scenario.config;
	}
}